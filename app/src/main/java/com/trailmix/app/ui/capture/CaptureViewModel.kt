package com.trailmix.app.ui.capture

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.model.TranscriptLine
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A selectable input in the capture menu; null device = automatic routing. */
data class InputOption(val label: String, val device: AudioDeviceInfo?)

data class CaptureUiState(
    val recording: Boolean = false,
    val elapsedLabel: String = "0:00",
    val livePartial: String = "",
    val lastFinalLine: String = "",
    val merging: Boolean = false,
    val speechAvailable: Boolean = true,
    val engineKind: EngineKind = EngineKind.NONE,
    val deviceAudioActive: Boolean = false,
    val inputOptions: List<InputOption> = emptyList(),
    val selectedInputIndex: Int = 0,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engine: CaptureEngine,
    private val aiProcessor: OnDeviceAiProcessor,
    private val notesRepository: NotesRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CaptureUiState())
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /** The user's own typed fragments — a live textarea buffer. */
    val fragments = MutableStateFlow("")

    private val transcriptLines = mutableListOf<TranscriptLine>()
    private var startedAtMs = 0L
    private var listenJob: Job? = null
    private var tickerJob: Job? = null

    fun startRecording() {
        if (_state.value.recording || _state.value.merging) return
        startedAtMs = System.currentTimeMillis()
        transcriptLines.clear()
        refreshInputOptions()
        _state.value = _state.value.copy(recording = true)
        CaptureService.start(appContext)
        listenJob = viewModelScope.launch {
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
                _state.value = _state.value.copy(
                    elapsedLabel = elapsedLabel(),
                    deviceAudioActive = engine.deviceAudioActive.value,
                )
                delay(1_000)
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
        _state.value = _state.value.copy(deviceAudioActive = false)
    }

    val deviceAudioSupported: Boolean
        get() = engine.deviceAudioSupported

    fun endAndMerge(onDone: (Long) -> Unit) {
        if (_state.value.merging) return
        _state.value = _state.value.copy(merging = true, recording = false)
        val durationMs = System.currentTimeMillis() - startedAtMs
        viewModelScope.launch {
            // Close the audio input and give the recognizer a moment to flush
            // its final utterance into the transcript before merging.
            engine.endInput()
            withTimeoutOrNull(5_000) { listenJob?.join() }
            stopCapture()
            val createdAt = System.currentTimeMillis()
            val typed = fragments.value
            val transcript = transcriptLines.toList()
            val result = aiProcessor.merge(
                typedFragments = typed,
                transcript = transcript,
                createdAtEpochMs = createdAt,
            )
            val id = notesRepository.saveMergedNote(
                title = result.title,
                segments = result.segments,
                transcript = transcript,
                typedFragments = typed,
                durationMs = durationMs,
                createdAtEpochMs = createdAt,
                mergedWithAi = result.usedOnDeviceAi,
            )
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
        _state.value = _state.value.copy(
            recording = false,
            livePartial = "",
            deviceAudioActive = false,
        )
    }

    private fun elapsedLabel(): String {
        val sec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    }

    override fun onCleared() {
        engine.endInput()
        stopCapture()
        super.onCleared()
    }
}
