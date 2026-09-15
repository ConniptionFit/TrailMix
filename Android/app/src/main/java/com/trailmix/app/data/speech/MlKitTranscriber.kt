package com.trailmix.app.data.speech

import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.trailmix.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device transcription via ML Kit GenAI speech recognition (AICore — the
 * same stack as the merge/chat model). Fed by [AudioPipeline]'s PCM pipe, so
 * TrailMix fully owns the audio path: selectable mic, mixed device audio, and
 * no system recognizer session (hence no start/stop chimes).
 *
 * Like every AI feature in this app it is fail-soft: [status] gates usage and
 * the capture engine falls back to the system recognizer when the model is
 * unavailable or still downloading.
 */
@Singleton
class MlKitTranscriber @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    /** AI-02: the user-picked ASR locale (Settings), defaulting to the prior hardcoded en-US. */
    private suspend fun currentLocale() =
        AsrLocales.toLocale(AsrLocales.fromTag(settingsRepository.asrLocaleTag.first()))

    private suspend fun newClient() = currentLocale().let { locale ->
        SpeechRecognition.getClient(speechRecognizerOptions { this.locale = locale })
    }

    suspend fun status(): Int = try {
        newClient().use { it.checkStatus() }
    } catch (e: Exception) {
        Log.w(TAG, "ASR status check failed: $e")
        FeatureStatus.UNAVAILABLE
    }

    /** Kick the AICore model download; safe to call repeatedly. */
    suspend fun download(): Boolean = try {
        var ok = true
        newClient().use { client ->
            client.download().collect { s -> if (s is DownloadStatus.DownloadFailed) ok = false }
        }
        ok
    } catch (e: Exception) {
        Log.w(TAG, "ASR model download failed: $e")
        false
    }

    /**
     * Stream recognition over raw 16 kHz mono PCM from [pfd]. Emits partial
     * hypotheses as they form and finalized utterances as the recognizer
     * endpoints them; completes once the pipe reaches EOF.
     */
    fun transcribe(pfd: ParcelFileDescriptor): Flow<SpeechEvent> = flow {
        val client = newClient()
        try {
            val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(pfd) }
            client.startRecognition(request).collect { response ->
                when (response) {
                    is SpeechRecognizerResponse.PartialTextResponse -> {
                        if (response.text.isNotBlank()) {
                            emit(SpeechEvent(finalizedUtterance = "", partialText = response.text))
                        }
                    }
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val text = response.text.trim()
                        if (text.isNotBlank()) {
                            emit(SpeechEvent(finalizedUtterance = text, partialText = ""))
                        }
                    }
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        // Keep whatever was transcribed so far; the session just ends.
                        Log.w(TAG, "ASR error: ${response.e}")
                    }
                    else -> Unit // CompletedResponse
                }
            }
        } finally {
            runCatching { client.stopRecognition() }
            runCatching { client.close() }
            // CAP-18: nobody else owns this fd on the graceful (non-abandon) path — the
            // caller (AudioPipeline.release()) only closes readSide itself when tearing
            // down early. runCatching matches that same call's guard, since a ParcelFileDescriptor
            // close is expected to be safe to call more than once but ML Kit's own handling
            // of the fd it was handed is not this class's contract to assume.
            runCatching { pfd.close() }
        }
    }

    private companion object {
        const val TAG = "TrailMixAsr"
    }
}
