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

    private val transcriptLines = mutableListOf<TranscriptLine>()
    private var startedAtMs = 0L
    private var listenJob: Job? = null
    private var tickerJob: Job? = null
    private var bumpJob: Job? = null
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
        capturedInCall = false
        attendees = emptyList()
        template = SummaryTemplate.NONE.name
        callEndedPromptFired = false
        _state.value = CaptureUiState(meetingTitle = meetingTitle)
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
                    transcriptLines += TranscriptLine(
                        label = elapsedLabel(),
                        text = event.finalizedUtterance,
                    )
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
            deviceAudioActive = false,
            deviceAudioSilent = false,
        )
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
                    _state.value = _state.value.copy(meetingTitle = current.title)
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
            val createdAt = if (resuming) resumeCreatedAt else System.currentTimeMillis()
            val typed = fragments.value
            val transcript = transcriptLines.toList()
            // CAP-11: an entirely empty session — nothing typed, nothing transcribed —
            // saves nothing at all. -1 tells the caller no note was created.
            if (MergePolicy.nothingToSave(typed, transcript)) {
                resumeNoteId = -1L
                _state.value = _state.value.copy(merging = false)
                // The caller navigates from this callback — NavController is main-thread-only
                // (this scope's default dispatcher crashed popBackStack when first shipped).
                withContext(Dispatchers.Main) { onDone(-1L) }
                return@launch
            }
            val result = aiProcessor.merge(
                typedFragments = typed,
                transcript = transcript,
                createdAtEpochMs = createdAt,
                attendees = attendees,
                templateGuidance = TemplateOptions.guidanceFor(
                    template,
                    settingsRepository.customSummaryTemplates.first(),
                ),
            )
            val id = if (resuming) {
                notesRepository.updateMergedNote(
                    id = resumeNoteId,
                    title = result.title,
                    segments = result.segments,
                    transcript = transcript,
                    typedFragments = typed,
                    durationMs = durationMs,
                    createdAtEpochMs = createdAt,
                    mergedWithAi = result.usedOnDeviceAi,
                    meetingTitle = _state.value.meetingTitle,
                    capturedInCall = capturedInCall,
                    attendees = attendees,
                    structuredSummary = result.structuredSummary,
                    template = template,
                )
                resumeNoteId
            } else {
                notesRepository.saveMergedNote(
                    title = result.title,
                    segments = result.segments,
                    transcript = transcript,
                    typedFragments = typed,
                    durationMs = durationMs,
                    createdAtEpochMs = createdAt,
                    mergedWithAi = result.usedOnDeviceAi,
                    meetingTitle = _state.value.meetingTitle,
                    capturedInCall = capturedInCall,
                    attendees = attendees,
                    structuredSummary = result.structuredSummary,
                    template = template,
                )
            }
            resumeNoteId = -1L
            _state.value = _state.value.copy(merging = false)
            withContext(Dispatchers.Main) { onDone(id) }
        }
    }

    fun cancel() {
        engine.endInput()
        stopCapture()
        resumeNoteId = -1L
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

    private companion object {
        const val SILENCE_PEAK = 64
        const val SILENT_HINT_AFTER_S = 5

        // CAP-12: how long a capture can run unpaused before the "still recording?"
        // nudge fires (and re-fires every interval after that). Not yet user-configurable
        // — see the Future Improvements row for making this a Settings value.
        const val BUMP_INTERVAL_MS = 60 * 60 * 1_000L
    }
}
