package com.trailmix.app.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.speech.OnDeviceSpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CaptureUiState(
    val recording: Boolean = false,
    val elapsedLabel: String = "0:00",
    val livePartial: String = "",
    val lastFinalLine: String = "",
    val merging: Boolean = false,
    val speechAvailable: Boolean = true,
)

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val speechRecognizer: OnDeviceSpeechRecognizer,
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
        _state.value = _state.value.copy(
            recording = true,
            speechAvailable = speechRecognizer.isAvailable(),
        )
        listenJob = viewModelScope.launch {
            speechRecognizer.listen().collect { event ->
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
                _state.value = _state.value.copy(elapsedLabel = elapsedLabel())
                delay(1_000)
            }
        }
    }

    fun endAndMerge(onDone: (Long) -> Unit) {
        if (_state.value.merging) return
        stopCapture()
        _state.value = _state.value.copy(merging = true)
        val durationMs = System.currentTimeMillis() - startedAtMs
        val createdAt = System.currentTimeMillis()
        val typed = fragments.value
        val transcript = transcriptLines.toList()
        viewModelScope.launch {
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
        stopCapture()
    }

    private fun stopCapture() {
        listenJob?.cancel()
        listenJob = null
        tickerJob?.cancel()
        tickerJob = null
        _state.value = _state.value.copy(recording = false, livePartial = "")
    }

    private fun elapsedLabel(): String {
        val sec = ((System.currentTimeMillis() - startedAtMs) / 1000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
    }

    override fun onCleared() {
        stopCapture()
        super.onCleared()
    }
}
