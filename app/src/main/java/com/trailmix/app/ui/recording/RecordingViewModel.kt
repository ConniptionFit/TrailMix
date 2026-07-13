package com.trailmix.app.ui.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.audio.AudioRecorder
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.speech.OnDeviceSpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RecordingUiState(
    val typedNotes: String = "",
    val transcriptFinal: String = "",
    val transcriptPartial: String = "",
    val elapsedMs: Long = 0L,
    val isPaused: Boolean = false,
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val processingMessage: String = "",
    val error: String? = null,
    val waveform: List<Float> = List(24) { 0.15f },
)

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val audioRecorder: AudioRecorder,
    private val speechRecognizer: OnDeviceSpeechRecognizer,
    private val aiProcessor: OnDeviceAiProcessor,
    private val notesRepository: NotesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var speechJob: Job? = null
    private var waveformJob: Job? = null
    private var startedAt = 0L
    private var accumulatedPausedMs = 0L
    private var pauseStartedAt = 0L
    private var audioFile: File? = null
    private val createdAtEpochMs = System.currentTimeMillis()

    fun onTypedNotesChange(value: String) {
        _uiState.update { it.copy(typedNotes = value) }
    }

    fun startSession() {
        if (_uiState.value.isRecording) return
        try {
            audioFile = audioRecorder.start()
            startedAt = System.currentTimeMillis()
            accumulatedPausedMs = 0L
            val speechOk = speechRecognizer.isAvailable()
            _uiState.update {
                it.copy(
                    isRecording = true,
                    isPaused = false,
                    error = if (speechOk) {
                        null
                    } else {
                        "On-device speech recognition isn’t available. You can still type notes."
                    },
                    elapsedMs = 0L,
                )
            }
            startTimer()
            startSpeech()
            startWaveform()
        } catch (e: Exception) {
            _uiState.update {
                it.copy(error = e.message ?: "Unable to start recording.")
            }
        }
    }

    fun togglePause() {
        val state = _uiState.value
        if (!state.isRecording || state.isProcessing) return
        if (state.isPaused) {
            audioRecorder.resume()
            accumulatedPausedMs += System.currentTimeMillis() - pauseStartedAt
            _uiState.update { it.copy(isPaused = false) }
            startSpeech()
            startWaveform()
        } else {
            audioRecorder.pause()
            pauseStartedAt = System.currentTimeMillis()
            speechJob?.cancel()
            waveformJob?.cancel()
            _uiState.update { it.copy(isPaused = true, transcriptPartial = "") }
        }
    }

    fun endSession(onSaved: (Long) -> Unit) {
        if (_uiState.value.isProcessing) return
        viewModelScope.launch {
            timerJob?.cancel()
            speechJob?.cancel()
            waveformJob?.cancel()
            val file = audioRecorder.stop()
            val duration = _uiState.value.elapsedMs
            val typed = _uiState.value.typedNotes
            val transcript = listOf(
                _uiState.value.transcriptFinal,
                _uiState.value.transcriptPartial,
            ).filter { it.isNotBlank() }.joinToString(" ").trim()

            _uiState.update {
                it.copy(
                    isRecording = false,
                    isPaused = false,
                    isProcessing = true,
                    processingMessage = "Preparing on-device AI…",
                    transcriptPartial = "",
                    transcriptFinal = transcript,
                )
            }

            try {
                _uiState.update { it.copy(processingMessage = "Merging notes with Gemini Nano…") }
                val processed = aiProcessor.process(
                    typedNotes = typed,
                    transcript = transcript,
                    createdAtEpochMs = createdAtEpochMs,
                    durationMs = duration,
                )
                _uiState.update { it.copy(processingMessage = "Saving…") }
                val saved = notesRepository.saveProcessedNote(
                    title = processed.title,
                    typedNotes = typed,
                    transcript = transcript,
                    summary = processed.summary,
                    mergedMarkdown = processed.mergedMarkdown,
                    durationMs = duration,
                    createdAtEpochMs = createdAtEpochMs,
                    audioPath = file?.absolutePath,
                )
                _uiState.update { it.copy(isProcessing = false) }
                onSaved(saved.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        error = e.message ?: "Failed to process note.",
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                val pausedExtra = if (_uiState.value.isPaused) {
                    System.currentTimeMillis() - pauseStartedAt
                } else {
                    0L
                }
                val elapsed = System.currentTimeMillis() - startedAt - accumulatedPausedMs - pausedExtra
                _uiState.update { it.copy(elapsedMs = elapsed.coerceAtLeast(0L)) }
                delay(200)
            }
        }
    }

    private fun startSpeech() {
        speechJob?.cancel()
        if (!speechRecognizer.isAvailable()) return
        speechJob = viewModelScope.launch {
            speechRecognizer.listen().collect { update ->
                _uiState.update {
                    it.copy(
                        transcriptFinal = update.finalText,
                        transcriptPartial = update.partialText,
                    )
                }
            }
        }
    }

    private fun startWaveform() {
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            while (isActive) {
                if (!_uiState.value.isPaused) {
                    val next = List(24) {
                        (0.12f + Math.random().toFloat() * 0.88f)
                    }
                    _uiState.update { it.copy(waveform = next) }
                }
                delay(120)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        speechJob?.cancel()
        waveformJob?.cancel()
        audioRecorder.cancel()
    }
}
