package com.trailmix.app.data.speech

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.trailmix.app.data.ai.MergePolicy
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TemplateOptions
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.service.CaptureService
import com.trailmix.app.ui.capture.CaptureUiState
import com.trailmix.app.ui.capture.DeviceAudioPrompt
import com.trailmix.app.ui.capture.InputOption
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns a capture session end to end (CAP-10). Previously this state and its collection
 * coroutines lived inside [com.trailmix.app.ui.capture.CaptureViewModel], which is scoped
 * to the Capture screen's back-stack entry — navigating away from Capture destroyed the
 * ViewModel, which cancelled its `viewModelScope` coroutines and, in `onCleared()`,
 * explicitly tore down the engine and the foreground service. In other words, leaving the
 * Capture screen silently killed the recording; a real bug, not just a missing UI chip.
 *
 * This singleton is process-scoped (its own [CoroutineScope], independent of any
 * ViewModel), so a session started here keeps running — audio pipeline, transcript
 * collection, and ticker — no matter what screen is on top. [CaptureViewModel] becomes a
 * thin adapter that starts a session (if none is active) and otherwise just observes.
 */
@Singleton
class CaptureSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: CaptureEngine,
    private val aiProcessor: OnDeviceAiProcessor,
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
    private val meetingSource: UpcomingMeetingSource,
    private val journal: CaptureJournalStore,
) {
    private val scope = CoroutineScope(SupervisorJob())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** The user's own typed fragments — a live textarea buffer. */
    val fragments = MutableStateFlow("")

    private val _liveLines = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val liveLines: StateFlow<List<TranscriptLine>> = _liveLines.asStateFlow()

    /** True while a session (recording, paused, or merging) is active — drives the Home chip. */
    val hasActiveSession: StateFlow<Boolean> = state
        .map { it.recording || it.paused || it.merging }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** CAP-12: fires while a capture has been actively recording (not paused) past
     * [BUMP_INTERVAL_MS] — [CaptureService] turns each emission into a "still recording?"
     * notification. Re-arms every interval as long as recording continues unpaused. */
    private val _bumpNudge = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bumpNudge: SharedFlow<Unit> = _bumpNudge.asSharedFlow()

    /** CAP-12: fires once when the phone call this capture started during appears to have
     * ended while still recording, so [CaptureService] can post a prompt even if the app
     * isn't foregrounded. Deliberately reuses the same no-permission AudioManager.mode read
     * [detectMeetingContext] already does for `capturedInCall` — never READ_PHONE_STATE. */
    private val _callEndedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val callEndedEvents: SharedFlow<Unit> = _callEndedEvents.asSharedFlow()

    /**
     * REL-09: a journal left behind by a capture that never reached End & Merge — the process
     * died mid-session. Non-null means the Home screen should offer to continue or complete
     * it. Populated once at construction, i.e. on the first launch after the crash.
     */
    private val _pendingRecovery = MutableStateFlow<PendingJournal?>(null)
    val pendingRecovery: StateFlow<PendingJournal?> = _pendingRecovery.asStateFlow()

    /** REL-09: true while [completeRecovered] is merging a recovered session into a note. */
    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

    private val transcriptLines = mutableListOf<TranscriptLine>()
    private var startedAtMs = 0L
    private var listenJob: Job? = null
    private var tickerJob: Job? = null
    private var bumpJob: Job? = null
    private var journalJob: Job? = null

    /** REL-09: last values written to the journal, so each flush only records what moved. */
    private var journaled = JournalSnapshot()

    /**
     * REL-09: creation timestamp a *recovered* fresh note should carry — the moment the lost
     * capture actually started, read back from its journal. Normal sessions leave this at 0
     * and keep the existing behaviour of stamping the note when the merge runs.
     */
    private var recoveredCreatedAtMs = 0L
    private var capturedInCall = false
    private var silentSeconds = 0
    private var priorDurationMs = 0L
    private var resumeCreatedAt = 0L
    private var resumeNoteId = -1L
    private var attendees: List<String> = emptyList()
    private var watchingForCallEnd = false
    private var callEndedPromptFired = false

    // AI-03: the STORED template value — a SummaryTemplate enum name or "custom:<name>".
    // Resolved to its prompt-guidance sentence only at merge time (TemplateOptions.guidanceFor),
    // so editing a custom template mid-capture picks up the latest wording.
    private var template: String = SummaryTemplate.NONE.name

    /** Which existing note (if any) the *currently active* session is attached to. */
    val activeResumeNoteId: Long
        get() = resumeNoteId.takeIf { state.value.recording || state.value.paused || state.value.merging } ?: -1L

    /**
     * Begin a brand-new session. No-ops if a session is already active — the caller
     * (CaptureViewModel) should treat that as "re-attach and just observe" instead.
     */
    fun beginSession(resumeNoteId: Long, meetingTitle: String?) {
        if (_state.value.recording || _state.value.paused || _state.value.merging) return
        this.resumeNoteId = resumeNoteId
        fragments.value = ""
        transcriptLines.clear()
        _liveLines.value = emptyList()
        priorDurationMs = 0L
        resumeCreatedAt = 0L
        recoveredCreatedAtMs = 0L
        capturedInCall = false
        attendees = emptyList()
        template = SummaryTemplate.NONE.name
        callEndedPromptFired = false
        _state.value = CaptureUiState(
            meetingTitle = meetingTitle,
            noteTitle = meetingTitle ?: CaptureUiState.UNTITLED,
        )
        // REL-09: open the crash journal before anything can be captured, so there is no
        // window where an utterance exists only in memory.
        journaled = JournalSnapshot()
        journal.begin(System.currentTimeMillis(), resumeNoteId)
        startJournalFlush()
        if (resumeNoteId <= 0) {
            // Fresh note: seed the template from the user's Settings default (UX-02);
            // a resumed note keeps whatever template it was created with (see applyResume).
            scope.launch {
                settingsRepository.defaultSummaryTemplate.first()?.let { template = it }
            }
        }
        startRecording(applyPriorResume = true)
    }

    /**
     * [applyPriorResume] only replays a saved note's content into the in-memory buffers
     * (CAP-07's resume-into-note flow) — it must be `false` when this is really [resume]
     * waking a *paused* session back up, or the transcript/fragments captured since the
     * pause would be overwritten with the stale on-disk copy.
     */
    private fun startRecording(applyPriorResume: Boolean) {
        startedAtMs = System.currentTimeMillis()
        refreshInputOptions()
        val mode = audioManager.mode
        watchingForCallEnd = !callEndedPromptFired &&
            (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)
        _state.value = _state.value.copy(recording = true, paused = false)
        CaptureService.start(appContext)
        listenJob = scope.launch {
            if (applyPriorResume && resumeNoteId > 0) applyResume()
            detectMeetingContext()
            val selected = _state.value.inputOptions
                .getOrNull(_state.value.selectedInputIndex)?.device
            val events = engine.begin(selected)
            _state.value = _state.value.copy(
                engineKind = engine.kind.value,
                speechAvailable = engine.kind.value != EngineKind.NONE,
            )
            events.collect { event ->
                if (event.finalizedUtterance.isNotBlank()) {
                    val finalized = TranscriptLine(
                        label = elapsedLabel(),
                        text = event.finalizedUtterance,
                    )
                    transcriptLines += finalized
                    // REL-09: journal it before it is anything but a value in RAM. Everything
                    // else here is display state that can be rebuilt; this line cannot.
                    journal.line(finalized)
                    _liveLines.value = transcriptLines.toList()
                    _state.value = _state.value.copy(
                        lastFinalLine = event.finalizedUtterance,
                        livePartial = "",
                    )
                } else if (event.partialText.isNotBlank()) {
                    _state.value = _state.value.copy(livePartial = event.partialText)
                }
            }
        }
        tickerJob = scope.launch {
            while (isActive) {
                val peak = engine.readAndResetPlaybackPeak()
                silentSeconds = when {
                    peak < 0 -> 0
                    peak < SILENCE_PEAK -> silentSeconds + 1
                    else -> 0
                }
                if (watchingForCallEnd) {
                    val callMode = audioManager.mode
                    if (callMode != AudioManager.MODE_IN_CALL && callMode != AudioManager.MODE_IN_COMMUNICATION) {
                        watchingForCallEnd = false
                        callEndedPromptFired = true
                        _state.value = _state.value.copy(callEndedPrompt = true)
                        _callEndedEvents.emit(Unit)
                    }
                }
                _state.value = _state.value.copy(
                    elapsedLabel = elapsedLabel(),
                    elapsedMs = currentDurationMs(),
                    elapsedBaseMs = elapsedBaseMs(),
                    deviceAudioActive = engine.deviceAudioActive.value,
                    deviceAudioSilent = silentSeconds >= SILENT_HINT_AFTER_S,
                )
                delay(1_000)
            }
        }
        armBumpTimer()
    }

    /**
     * CAP-12: releases the mic and freezes the elapsed timer without ending the
     * session — the transcript so far, typed fragments, and note identity all stay in
     * memory so [resume] can pick straight back up. Driven by the ongoing notification's
     * Pause action (and an equivalent in-app control).
     */
    fun pause() {
        if (!_state.value.recording) return
        val frozenLabel = elapsedLabel()
        priorDurationMs += System.currentTimeMillis() - startedAtMs
        listenJob?.cancel()
        listenJob = null
        tickerJob?.cancel()
        tickerJob = null
        disarmBumpTimer()
        engine.endInput()
        engine.detachDeviceAudio()
        silentSeconds = 0
        _state.value = _state.value.copy(
            recording = false,
            paused = true,
            livePartial = "",
            elapsedLabel = frozenLabel,
            // priorDurationMs was just advanced above and `recording` is still true here,
            // so currentDurationMs() would double-count the final delta.
            elapsedMs = priorDurationMs,
            elapsedBaseMs = System.currentTimeMillis() - priorDurationMs,
            deviceAudioActive = false,
            deviceAudioSilent = false,
        )
        // REL-09: the timeline just froze — pin the final duration now rather than leaving
        // the journal to report whatever the last periodic flush happened to catch.
        flushJournal()
    }

    /** CAP-12: the Resume half of [pause] — restarts the mic/engine and the elapsed
     * timer continues from where it was frozen (via `priorDurationMs`), reusing the same
     * transcript/fragment buffers rather than starting a new session. */
    fun resume() {
        if (!_state.value.paused) return
        startRecording(applyPriorResume = false)
    }

    fun requestDeviceAudio() {
        if (engine.kind.value != EngineKind.MLKIT) return
        if (_state.value.deviceAudioActive) return
        scope.launch {
            val explained = settingsRepository.projectionExplainerShown.first()
            _state.value = _state.value.copy(
                deviceAudioPrompt =
                    if (explained) DeviceAudioPrompt.ASK else DeviceAudioPrompt.EXPLAIN_THEN_ASK,
            )
        }
    }

    private suspend fun applyResume() {
        val note = notesRepository.getNote(resumeNoteId) ?: return
        transcriptLines.clear()
        transcriptLines.addAll(note.transcript)
        _liveLines.value = transcriptLines.toList()
        fragments.value = note.typedFragments
        priorDurationMs = note.durationMs
        resumeCreatedAt = note.createdAtEpochMs
        capturedInCall = note.capturedInCall
        attendees = note.attendees
        template = note.template ?: SummaryTemplate.NONE.name
        _state.value = _state.value.copy(
            meetingTitle = note.meetingTitle ?: _state.value.meetingTitle,
            // CAP-13: resuming into a real note — the notification should name it.
            noteTitle = note.title,
            lastFinalLine = transcriptLines.lastOrNull()?.text ?: "",
        )
    }

    fun consumeDeviceAudioPrompt() {
        _state.value = _state.value.copy(deviceAudioPrompt = DeviceAudioPrompt.NONE)
    }

    fun markExplainerShown() {
        scope.launch { settingsRepository.markProjectionExplainerShown() }
    }

    private fun detectMeetingContext() {
        capturedInCall = capturedInCall ||
            audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        scope.launch {
            val current = meetingSource.currentEvent()
            if (current != null && _state.value.recording) {
                if (_state.value.meetingTitle == null) {
                    _state.value = _state.value.copy(
                        meetingTitle = current.title,
                        // Only name the note after the meeting if nothing better is known.
                        noteTitle = _state.value.noteTitle.takeIf { it != CaptureUiState.UNTITLED }
                            ?: current.title,
                    )
                }
                if (attendees.isEmpty()) {
                    attendees = meetingSource.attendeesFor(current.eventId)
                }
            }
        }
    }

    fun selectInput(index: Int) {
        val option = _state.value.inputOptions.getOrNull(index) ?: return
        _state.value = _state.value.copy(selectedInputIndex = index)
        engine.setPreferredDevice(option.device)
    }

    fun onProjectionGranted(resultCode: Int, data: Intent) {
        CaptureService.attachProjection(appContext, resultCode, data)
        scope.launch {
            delay(500)
            _state.value = _state.value.copy(deviceAudioActive = engine.deviceAudioActive.value)
        }
    }

    fun disableDeviceAudio() {
        engine.detachDeviceAudio()
        silentSeconds = 0
        _state.value = _state.value.copy(deviceAudioActive = false, deviceAudioSilent = false)
    }

    /** [stored] is a [TemplateOption.stored] value — enum name or `custom:<name>` (AI-03). */
    fun setTemplate(stored: String) {
        template = stored
    }

    val currentTemplate: String get() = template

    val deviceAudioSupported: Boolean
        get() = engine.deviceAudioSupported

    fun endAndMerge(onDone: (Long) -> Unit) {
        if (_state.value.merging) return
        // Must read before flipping `recording` below — currentDurationMs() branches on
        // it (paused vs. still-running) to avoid double-counting the paused gap.
        val durationMs = currentDurationMs()
        _state.value = _state.value.copy(merging = true, recording = false)
        scope.launch {
            engine.endInput()
            withTimeoutOrNull(5_000) { listenJob?.join() }
            stopCapture()
            val resuming = resumeNoteId > 0
            val createdAt = when {
                // resumeCreatedAt is normally filled in by applyResume(), but a session
                // *recovered* into an existing note (REL-09) never runs it — and neither
                // does a resume whose note has since vanished. Re-read rather than stamping
                // the note with epoch zero.
                resuming -> resumeCreatedAt.takeIf { it > 0 }
                    ?: notesRepository.getNote(resumeNoteId)?.createdAtEpochMs
                    ?: System.currentTimeMillis()
                // REL-09: a recovered session is stamped with when the capture really began,
                // not when it was rescued — the two can be a day apart.
                recoveredCreatedAtMs > 0 -> recoveredCreatedAtMs
                else -> System.currentTimeMillis()
            }
            val id = mergeAndSave(
                noteId = resumeNoteId,
                typed = fragments.value,
                transcript = transcriptLines.toList(),
                durationMs = durationMs,
                createdAtEpochMs = createdAt,
                meetingTitle = _state.value.meetingTitle,
                capturedInCall = capturedInCall,
                attendees = attendees,
                template = template,
            )
            // REL-09: the capture is now durably a note (or was empty and deliberately not
            // saved), so the journal has done its job. Only now — a crash at any point
            // before this line still leaves the transcript recoverable.
            journal.finish()
            resumeNoteId = -1L
            recoveredCreatedAtMs = 0L
            _state.value = _state.value.copy(merging = false)
            // The caller navigates from this callback — NavController is main-thread-only
            // (this scope's default dispatcher crashed popBackStack when first shipped).
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    /**
     * Merge a captured session into a note and return its id, or -1 when there was nothing
     * worth saving (CAP-11).
     *
     * Split out of [endAndMerge] for REL-09: a session recovered from a crash journal has to
     * travel exactly the same path — same template resolution, same AI-05 style steering,
     * same AI-06 title protection on re-merge, same automatic export — and a second copy of
     * this that drifted would mean recovered notes were quietly second-class.
     */
    @Suppress("LongParameterList")
    private suspend fun mergeAndSave(
        noteId: Long,
        typed: String,
        transcript: List<TranscriptLine>,
        durationMs: Long,
        createdAtEpochMs: Long,
        meetingTitle: String?,
        capturedInCall: Boolean,
        attendees: List<String>,
        template: String,
    ): Long {
        // CAP-11: an entirely empty session — nothing typed, nothing transcribed —
        // saves nothing at all. -1 tells the caller no note was created.
        if (MergePolicy.nothingToSave(typed, transcript)) return -1L
        val customTemplates = settingsRepository.customSummaryTemplates.first()
        val result = aiProcessor.merge(
            typedFragments = typed,
            transcript = transcript,
            createdAtEpochMs = createdAtEpochMs,
            attendees = attendees,
            templateGuidance = TemplateOptions.guidanceFor(template, customTemplates),
            // AI-05: the template also steers the zero-AI path now, so "Conference talk"
            // shapes the note on a device with no Gemini Nano.
            style = TemplateOptions.styleFor(template, customTemplates),
        )
        if (noteId > 0) {
            notesRepository.updateMergedNote(
                id = noteId,
                title = result.title,
                segments = result.segments,
                transcript = transcript,
                typedFragments = typed,
                durationMs = durationMs,
                createdAtEpochMs = createdAtEpochMs,
                mergedWithAi = result.usedOnDeviceAi,
                meetingTitle = meetingTitle,
                capturedInCall = capturedInCall,
                attendees = attendees,
                structuredSummary = result.structuredSummary,
                template = template,
            )
            return noteId
        }
        return notesRepository.saveMergedNote(
            title = result.title,
            segments = result.segments,
            transcript = transcript,
            typedFragments = typed,
            durationMs = durationMs,
            createdAtEpochMs = createdAtEpochMs,
            mergedWithAi = result.usedOnDeviceAi,
            meetingTitle = meetingTitle,
            capturedInCall = capturedInCall,
            attendees = attendees,
            structuredSummary = result.structuredSummary,
            template = template,
        )
    }

    fun cancel() {
        engine.endInput()
        stopCapture()
        // REL-09: an explicit discard is the user saying they don't want this capture. That
        // is the one case where the journal should go without being offered back to them.
        journal.discardCurrent()
        resumeNoteId = -1L
        recoveredCreatedAtMs = 0L
    }

    /** CAP-12: clears the one-shot call-ended dialog/notification without ending the
     * session — "finish later" just keeps recording. */
    fun consumeCallEndedPrompt() {
        _state.value = _state.value.copy(callEndedPrompt = false)
    }

    private fun refreshInputOptions() {
        val options = buildList {
            add(InputOption("Auto (follow system)", null))
            engine.availableInputDevices().forEach { add(InputOption(friendlyName(it), it)) }
        }
        val keepIndex = _state.value.selectedInputIndex.takeIf { it < options.size } ?: 0
        _state.value = _state.value.copy(inputOptions = options, selectedInputIndex = keepIndex)
    }

    private fun friendlyName(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET ->
            device.productName.toString().ifBlank { "Bluetooth mic" }
        else -> device.productName.toString().ifBlank { "Microphone" }
    }.let { base ->
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && device.address.isNotBlank()) {
            "$base (${device.address})"
        } else {
            base
        }
    }

    private fun stopCapture() {
        listenJob?.cancel()
        listenJob = null
        tickerJob?.cancel()
        tickerJob = null
        // REL-09: one last flush before the periodic writer stops, so the journal's duration
        // and typed fragments match the session that is about to be merged.
        flushJournal()
        journalJob?.cancel()
        journalJob = null
        disarmBumpTimer()
        engine.detachDeviceAudio()
        CaptureService.stop(appContext)
        silentSeconds = 0
        _state.value = _state.value.copy(
            recording = false,
            paused = false,
            livePartial = "",
            deviceAudioActive = false,
            deviceAudioSilent = false,
        )
    }

    /** Elapsed time in ms as of right now: frozen at `priorDurationMs` while paused
     * (or merging, or idle) — only still-recording adds the live `(now - startedAtMs)`
     * delta on top. Anything reading the timer (notification, End & Merge's saved
     * duration, the elapsed label) goes through this single branch. */
    private fun currentDurationMs(): Long =
        if (_state.value.recording) priorDurationMs + (System.currentTimeMillis() - startedAtMs) else priorDurationMs

    /** CAP-15: the instant the timer counts up from — the chronometer's base. Stable while
     * recording; moves only when the timeline is redefined (resume adopts a note's prior
     * duration, pause freezes it), which is exactly when the notification must be re-posted. */
    private fun elapsedBaseMs(): Long = System.currentTimeMillis() - currentDurationMs()

    fun elapsedLabel(): String {
        val sec = (currentDurationMs() / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    }

    /** CAP-12: (re)starts the "still recording?" countdown. Called every time recording
     * actually starts or resumes; [pause]/[stopCapture] cancel it — a paused or ended
     * session should never nag. */
    private fun armBumpTimer() {
        bumpJob?.cancel()
        bumpJob = scope.launch {
            while (isActive) {
                delay(BUMP_INTERVAL_MS)
                _bumpNudge.emit(Unit)
            }
        }
    }

    private fun disarmBumpTimer() {
        bumpJob?.cancel()
        bumpJob = null
    }

    // ── REL-09: crash journal ──────────────────────────────────────────────

    /** Everything the journal tracks besides the transcript itself, as last written. */
    private data class JournalSnapshot(
        val durationMs: Long = -1L,
        val fragments: String? = null,
        val title: String? = null,
        val meeting: String? = null,
        val template: String? = null,
        val attendees: List<String>? = null,
        val inCall: Boolean? = null,
        val resumeNoteId: Long? = null,
    )

    /**
     * Periodically record the session's non-transcript state.
     *
     * Runs for the whole session rather than only while recording, deliberately: typing into
     * the fragments box is a normal thing to do while *paused*, and losing that to a crash
     * would be just as annoying as losing speech. Utterances are journaled the instant they
     * are recognized, so this timer only governs how stale the metadata can be — a few
     * seconds of elapsed duration, at the cost of one small append instead of one per
     * keystroke.
     */
    private fun startJournalFlush() {
        journalJob?.cancel()
        journalJob = scope.launch {
            while (isActive) {
                delay(JOURNAL_FLUSH_INTERVAL_MS)
                flushJournal()
            }
        }
    }

    /** Append a delta for whatever changed since the last flush; writes nothing if nothing did. */
    private fun flushJournal() {
        val snapshot = _state.value
        val duration = currentDurationMs()
        val typed = fragments.value
        journal.delta(
            CaptureJournal.deltaRecord(
                durationMs = duration.takeIf { it != journaled.durationMs },
                typedFragments = typed.takeIf { it != journaled.fragments },
                noteTitle = snapshot.noteTitle.takeIf { it != journaled.title },
                meetingTitle = snapshot.meetingTitle?.takeIf { it != journaled.meeting },
                template = template.takeIf { it != journaled.template },
                attendees = attendees.takeIf { it != journaled.attendees },
                capturedInCall = capturedInCall.takeIf { it != journaled.inCall },
                resumeNoteId = resumeNoteId.takeIf { it != journaled.resumeNoteId },
            ),
        )
        journaled = JournalSnapshot(
            durationMs = duration,
            fragments = typed,
            title = snapshot.noteTitle,
            meeting = snapshot.meetingTitle,
            template = template,
            attendees = attendees,
            inCall = capturedInCall,
            resumeNoteId = resumeNoteId,
        )
    }

    /**
     * Look for a capture the app never got to finish. Runs once at construction — i.e. on the
     * first launch after the process died — and again after each recovery is resolved, so a
     * device that crashed twice surfaces both, one prompt at a time.
     */
    private suspend fun refreshRecovery() {
        if (_state.value.recording || _state.value.paused || _state.value.merging) return
        _pendingRecovery.value = journal.pending().firstOrNull()
    }

    /**
     * Pick the recovered capture back up and keep recording into it (REL-09).
     *
     * The buffers are restored exactly as [applyResume] restores a saved note's, and the
     * *same* journal file is adopted rather than started fresh, so a second crash recovers a
     * second time with no special case.
     */
    fun continueRecovered() {
        val pending = _pendingRecovery.value ?: return
        if (_state.value.recording || _state.value.paused || _state.value.merging) return
        _pendingRecovery.value = null
        val recovered = pending.session

        resumeNoteId = recovered.resumeNoteId
        fragments.value = recovered.typedFragments
        transcriptLines.clear()
        transcriptLines.addAll(recovered.transcript)
        _liveLines.value = transcriptLines.toList()
        priorDurationMs = recovered.durationMs
        resumeCreatedAt = 0L
        recoveredCreatedAtMs = recovered.startedAtEpochMs
        capturedInCall = recovered.capturedInCall
        attendees = recovered.attendees
        template = recovered.template
        callEndedPromptFired = false
        _state.value = CaptureUiState(
            meetingTitle = recovered.meetingTitle,
            noteTitle = recovered.noteTitle.ifBlank {
                recovered.meetingTitle ?: CaptureUiState.UNTITLED
            },
            lastFinalLine = transcriptLines.lastOrNull()?.text ?: "",
        )

        journaled = JournalSnapshot()
        journal.adopt(pending.id)
        startJournalFlush()
        // false: the in-memory buffers ARE the recovered content — the CAP-12 trap applies
        // here for the same reason it applies to waking a paused session.
        startRecording(applyPriorResume = false)
    }

    /**
     * Finish the recovered capture as it stands: merge it into a note without recording any
     * more (REL-09). [onDone] receives the note id on the main thread, or -1 if the journal
     * turned out to hold nothing mergeable.
     *
     * This can take minutes on a long session — it is the same chunked on-device merge End &
     * Merge runs — which is why [recovering] exists for the UI to show progress against.
     */
    fun completeRecovered(onDone: (Long) -> Unit) {
        val pending = _pendingRecovery.value ?: return
        if (_recovering.value || _state.value.recording || _state.value.paused || _state.value.merging) return
        _pendingRecovery.value = null
        _recovering.value = true
        scope.launch {
            val recovered = pending.session
            val createdAt = if (recovered.resumeNoteId > 0) {
                notesRepository.getNote(recovered.resumeNoteId)?.createdAtEpochMs
                    ?: recovered.startedAtEpochMs
            } else {
                recovered.startedAtEpochMs
            }
            val id = runCatching {
                mergeAndSave(
                    noteId = recovered.resumeNoteId,
                    typed = recovered.typedFragments,
                    transcript = recovered.transcript,
                    durationMs = recovered.durationMs,
                    createdAtEpochMs = createdAt,
                    meetingTitle = recovered.meetingTitle,
                    capturedInCall = recovered.capturedInCall,
                    attendees = recovered.attendees,
                    template = recovered.template,
                )
            }.getOrElse { -1L }
            // Only discard the journal once the note exists. If the merge threw, the
            // transcript stays on disk and is offered again rather than being thrown away
            // on the strength of a failed rescue.
            if (id > 0) journal.discard(pending.id)
            _recovering.value = false
            withContext(Dispatchers.Main) { onDone(id) }
            refreshRecovery()
        }
    }

    /** Throw the recovered capture away for good (REL-09) — the UI confirms first. */
    fun discardRecovered() {
        val pending = _pendingRecovery.value ?: return
        _pendingRecovery.value = null
        scope.launch {
            journal.discard(pending.id)
            refreshRecovery()
        }
    }

    /**
     * Not now: hide the prompt but keep the journal, so it is offered again next launch.
     * Dismissing a dialog must never be a destructive act on an hour of transcript.
     */
    fun dismissRecovery() {
        _pendingRecovery.value = null
    }

    init {
        scope.launch { refreshRecovery() }
    }

    private companion object {
        const val SILENCE_PEAK = 64
        const val SILENT_HINT_AFTER_S = 5

        /**
         * REL-09: how often the session's metadata (elapsed duration, typed fragments,
         * detected meeting) is appended to the crash journal. Transcript lines do NOT wait
         * for this — they are written as they are recognized. Short enough that a crash
         * costs seconds of elapsed time, long enough that a 90-minute session adds ~540
         * tiny records rather than one per keystroke.
         */
        const val JOURNAL_FLUSH_INTERVAL_MS = 10_000L

        // CAP-12: how long a capture can run unpaused before the "still recording?"
        // nudge fires (and re-fires every interval after that). Not yet user-configurable
        // — see the Future Improvements row for making this a Settings value.
        const val BUMP_INTERVAL_MS = 60 * 60 * 1_000L
    }
}
