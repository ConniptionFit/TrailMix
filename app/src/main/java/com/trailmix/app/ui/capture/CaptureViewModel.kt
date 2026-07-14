package com.trailmix.app.ui.capture

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.speech.CaptureEngine
import com.trailmix.app.data.speech.EngineKind
import com.trailmix.app.service.CaptureService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A selectable input in the capture menu; null device = automatic routing. */
data class InputOption(val label: String, val device: AudioDeviceInfo?)

/** What the screen should do about the device-audio consent right now. */
enum class DeviceAudioPrompt { NONE, EXPLAIN_THEN_ASK, ASK }

data class CaptureUiState(
    val recording: Boolean = false,
    val elapsedLabel: String = "0:00",
    val livePartial: String = "",
    val lastFinalLine: String = "",
    val merging: Boolean = false,
    val speechAvailable: Boolean = true,
    val engineKind: EngineKind = EngineKind.NONE,
    val deviceAudioActive: Boolean = false,
    /** Device audio attached but delivering pure silence for a while. */
    val deviceAudioSilent: Boolean = false,
    val inputOptions: List<InputOption> = emptyList(),
    val selectedInputIndex: Int = 0,
    val deviceAudioPrompt: DeviceAudioPrompt = DeviceAudioPrompt.NONE,
    /** Calendar event this capture is for (from the Home card or detected live). */
    val meetingTitle: String? = null,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
    private val engine: CaptureEngine,
    private val aiProcessor: OnDeviceAiProcessor,
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
    private val meetingSource: UpcomingMeetingSource,
) : ViewModel() {

    /** > 0 when this session resumes an existing note (re-merges into it). */
    private val resumeNoteId: Long =
        savedStateHandle.get<Long>("resumeNoteId")?.takeIf { it > 0 } ?: -1L

    private val _state = MutableStateFlow(
        CaptureUiState(meetingTitle = savedStateHandle.get<String>("title")?.takeIf { it.isNotBlank() }),
    )
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** The user's own typed fragments — a live textarea buffer. */
    val fragments = MutableStateFlow("")

    /** Finalized transcript so far — drives the expanded live-transcript view. */
    private val _liveLines = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val liveLines: StateFlow<List<TranscriptLine>> = _liveLines.asStateFlow()

    private val transcriptLines = mutableListOf<TranscriptLine>()
    private var startedAtMs = 0L
    private var listenJob: Job? = null
    private var tickerJob: Job? = null
    private var capturedInCall = false
    private var silentSeconds = 0
    /** Duration already accumulated in the note being resumed (ms); 0 for a fresh note. */
    private var priorDurationMs = 0L
    /** Original creation timestamp to preserve when resuming; 0 for a fresh note. */
    private var resumeCreatedAt = 0L
    private var seeded = false

    fun startRecording() {
        if (_state.value.recording || _state.value.merging) return
        startedAtMs = System.currentTimeMillis()
        transcriptLines.clear()
        _liveLines.value = emptyList()
        refreshInputOptions()
        _state.value = _state.value.copy(recording = true)
        CaptureService.start(appContext)
        listenJob = viewModelScope.launch {
            if (resumeNoteId > 0 && !seeded) applyResume()
            seeded = true
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
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val peak = engine.readAndResetPlaybackPeak()
                silentSeconds = when {
                    peak < 0 -> 0                       // lane not attached
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

    /**
     * User opted into device-audio capture from the in-call menu. Capture is
     * mic-only by default (Android can't reliably capture calls/YouTube — see
     * the silence hint), so this is an explicit action. Fires the one-time
     * explainer the first time, since Android's system dialog talks about
     * "recording your screen" when all TrailMix takes from it is the audio.
     */
    fun requestDeviceAudio() {
        if (engine.kind.value != EngineKind.MLKIT) return
        if (_state.value.deviceAudioActive) return
        viewModelScope.launch {
            val explained = settingsRepository.projectionExplainerShown.first()
            _state.value = _state.value.copy(
                deviceAudioPrompt =
                    if (explained) DeviceAudioPrompt.ASK else DeviceAudioPrompt.EXPLAIN_THEN_ASK,
            )
        }
    }

    /** Seed the session from the note being resumed (transcript, fragments, metadata). */
    private suspend fun applyResume() {
        val note = notesRepository.getNote(resumeNoteId) ?: return
        transcriptLines.clear()
        transcriptLines.addAll(note.transcript)
        _liveLines.value = transcriptLines.toList()
        fragments.value = note.typedFragments
        priorDurationMs = note.durationMs
        resumeCreatedAt = note.createdAtEpochMs
        capturedInCall = note.capturedInCall
        _state.value = _state.value.copy(
            meetingTitle = note.meetingTitle ?: _state.value.meetingTitle,
            lastFinalLine = transcriptLines.lastOrNull()?.text ?: "",
        )
    }

    fun consumeDeviceAudioPrompt() {
        _state.value = _state.value.copy(deviceAudioPrompt = DeviceAudioPrompt.NONE)
    }

    fun markExplainerShown() {
        viewModelScope.launch { settingsRepository.markProjectionExplainerShown() }
    }

    /** Tag the capture with the meeting context it started in (metadata only). */
    private fun detectMeetingContext() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Sticky across a resume: keep an earlier in-call flag even if we're not in a call now.
        capturedInCall = capturedInCall ||
            audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        if (_state.value.meetingTitle == null) {
            viewModelScope.launch {
                val current = meetingSource.currentEvent()
                if (current != null && _state.value.recording) {
                    _state.value = _state.value.copy(meetingTitle = current.title)
                }
            }
        }
    }

    /** From the 3-dot menu: reroute the mic (index 0 is Auto). */
    fun selectInput(index: Int) {
        val option = _state.value.inputOptions.getOrNull(index) ?: return
        _state.value = _state.value.copy(selectedInputIndex = index)
        engine.setPreferredDevice(option.device)
    }

    /** Device-audio consent came back from the system dialog. */
    fun onProjectionGranted(resultCode: Int, data: Intent) {
        CaptureService.attachProjection(appContext, resultCode, data)
        viewModelScope.launch {
            // The service attaches asynchronously; reflect the result shortly after.
            delay(500)
            _state.value = _state.value.copy(deviceAudioActive = engine.deviceAudioActive.value)
        }
    }

    fun disableDeviceAudio() {
        engine.detachDeviceAudio()
        silentSeconds = 0
        _state.value = _state.value.copy(deviceAudioActive = false, deviceAudioSilent = false)
    }

    val deviceAudioSupported: Boolean
        get() = engine.deviceAudioSupported

    fun endAndMerge(onDone: (Long) -> Unit) {
        if (_state.value.merging) return
        _state.value = _state.value.copy(merging = true, recording = false)
        val durationMs = priorDurationMs + (System.currentTimeMillis() - startedAtMs)
        viewModelScope.launch {
            // Close the audio input and give the recognizer a moment to flush
            // its final utterance into the transcript before merging.
            engine.endInput()
            withTimeoutOrNull(5_000) { listenJob?.join() }
            stopCapture()
            val resuming = resumeNoteId > 0
            val createdAt = if (resuming) resumeCreatedAt else System.currentTimeMillis()
            val typed = fragments.value
            val transcript = transcriptLines.toList()
            val result = aiProcessor.merge(
                typedFragments = typed,
                transcript = transcript,
                createdAtEpochMs = createdAt,
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
                )
            }
            _state.value = _state.value.copy(merging = false)
            onDone(id)
        }
    }

    fun cancel() {
        engine.endInput()
        stopCapture()
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

    private fun elapsedLabel(): String {
        val elapsed = priorDurationMs + (System.currentTimeMillis() - startedAtMs)
        val sec = (elapsed / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    }

    override fun onCleared() {
        engine.endInput()
        stopCapture()
        super.onCleared()
    }

    private companion object {
        /** 16-bit peaks below this are indistinguishable from digital silence. */
        const val SILENCE_PEAK = 64
        const val SILENT_HINT_AFTER_S = 5
    }
}
