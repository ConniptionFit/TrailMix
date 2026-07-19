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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** The user's own typed fragments — a live textarea buffer. */
    val fragments = MutableStateFlow("")

    private val _liveLines = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val liveLines: StateFlow<List<TranscriptLine>> = _liveLines.asStateFlow()

    /** True while a session (recording or merging) is active — drives the Home chip. */
    val hasActiveSession: StateFlow<Boolean> = state
        .map { it.recording || it.merging }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val transcriptLines = mutableListOf<TranscriptLine>()
    private var startedAtMs = 0L
    private var listenJob: Job? = null
    private var tickerJob: Job? = null
    private var capturedInCall = false
    private var silentSeconds = 0
    private var priorDurationMs = 0L
    private var resumeCreatedAt = 0L
    private var resumeNoteId = -1L
    private var attendees: List<String> = emptyList()
    private var template: SummaryTemplate = SummaryTemplate.NONE

    /** Which existing note (if any) the *currently active* session is attached to. */
    val activeResumeNoteId: Long get() = resumeNoteId.takeIf { state.value.recording || state.value.merging } ?: -1L

    /**
     * Begin a brand-new session. No-ops if a session is already active — the caller
     * (CaptureViewModel) should treat that as "re-attach and just observe" instead.
     */
    fun beginSession(resumeNoteId: Long, meetingTitle: String?) {
        if (_state.value.recording || _state.value.merging) return
        this.resumeNoteId = resumeNoteId
        fragments.value = ""
        transcriptLines.clear()
        _liveLines.value = emptyList()
        priorDurationMs = 0L
        resumeCreatedAt = 0L
        capturedInCall = false
        attendees = emptyList()
        template = SummaryTemplate.NONE
        _state.value = CaptureUiState(meetingTitle = meetingTitle)
        if (resumeNoteId <= 0) {
            // Fresh note: seed the template from the user's Settings default (UX-02);
            // a resumed note keeps whatever template it was created with (see applyResume).
            scope.launch { template = SummaryTemplate.fromStored(settingsRepository.defaultSummaryTemplate.first()) }
        }
        startRecording()
    }

    private fun startRecording() {
        startedAtMs = System.currentTimeMillis()
        refreshInputOptions()
        _state.value = _state.value.copy(recording = true)
        CaptureService.start(appContext)
        listenJob = scope.launch {
            if (resumeNoteId > 0) applyResume()
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
                _state.value = _state.value.copy(
                    elapsedLabel = elapsedLabel(),
                    deviceAudioActive = engine.deviceAudioActive.value,
                    deviceAudioSilent = silentSeconds >= SILENT_HINT_AFTER_S,
                )
                delay(1_000)
            }
        }
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
        template = SummaryTemplate.fromStored(note.template)
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
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    fun setTemplate(t: SummaryTemplate) {
        template = t
    }

    val currentTemplate: SummaryTemplate get() = template

    val deviceAudioSupported: Boolean
        get() = engine.deviceAudioSupported

    fun endAndMerge(onDone: (Long) -> Unit) {
        if (_state.value.merging) return
        _state.value = _state.value.copy(merging = true, recording = false)
        val durationMs = priorDurationMs + (System.currentTimeMillis() - startedAtMs)
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
                template = template,
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
                    template = template.name,
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
                    template = template.name,
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
        engine.detachDeviceAudio()
        CaptureService.stop(appContext)
        silentSeconds = 0
        _state.value = _state.value.copy(
            recording = false,
            livePartial = "",
            deviceAudioActive = false,
            deviceAudioSilent = false,
        )
    }

    fun elapsedLabel(): String {
        val elapsed = priorDurationMs + (System.currentTimeMillis() - startedAtMs)
        val sec = (elapsed / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    }

    private companion object {
        const val SILENCE_PEAK = 64
        const val SILENT_HINT_AFTER_S = 5
    }
}
