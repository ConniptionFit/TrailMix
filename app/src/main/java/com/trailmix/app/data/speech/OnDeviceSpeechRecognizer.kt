package com.trailmix.app.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

data class TranscriptUpdate(
    val finalText: String,
    val partialText: String,
)

@Singleton
class OnDeviceSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(): Flow<TranscriptUpdate> {
        if (!isAvailable()) return flowOf(TranscriptUpdate("", ""))

        return callbackFlow {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val finals = StringBuilder()
            var restart = true

            fun startListening() {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer.startListening(intent)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (!restart) return
                    // Recover from no-match / timeout by restarting continuous dictation.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT
                    ) {
                        startListening()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) {
                        if (finals.isNotEmpty()) finals.append(' ')
                        finals.append(text.trim())
                        trySend(TranscriptUpdate(finals.toString(), ""))
                    }
                    if (restart) startListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    trySend(TranscriptUpdate(finals.toString(), partial))
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            startListening()

            awaitClose {
                restart = false
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }
}
