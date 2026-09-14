package com.trailmix.app.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

data class SpeechEvent(
    /** A newly finalized utterance (empty when only the partial changed). */
    val finalizedUtterance: String,
    /** The in-flight partial hypothesis. */
    val partialText: String,
    /**
     * CAP-21: true when this lane has permanently given up and will never emit again (e.g.
     * [SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS]) — every *other* error already retries
     * on a backoff (CAP-16), so this is specifically the dead-end case, not ordinary struggle.
     * Previously silent: the session kept showing "Listening…" forever with nothing to show
     * for it. Defaults false so [MlKitTranscriber]'s emits (which have no equivalent terminal
     * state today) don't need to know this field exists.
     */
    val recognizerDead: Boolean = false,
)

/**
 * Continuous dictation on the device's own recognizer — the fallback lane, used when the
 * ML Kit/AICore pipeline is unavailable or still downloading its model.
 *
 * Privacy: uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] exclusively —
 * recognition never leaves the device, and no audio is ever written to disk by
 * this app (the recognizer consumes the mic stream in memory).
 *
 * **CAP-17 (v1.19.0): this lane had never produced a single word.** [SpeechRecognizer] is
 * main-thread-only — `SpeechRecognizerImpl.checkIsCalledFromMainThread()` throws
 * `"SpeechRecognizer should be used only from the application's main thread"` from the
 * factory, `setRecognitionListener`, `startListening` and `cancel` — and a `callbackFlow`
 * runs its builder block in the *collector's* context, which for a capture session is
 * `CaptureSessionManager.scope`, i.e. [kotlinx.coroutines.Dispatchers.Default]. So the very
 * first call threw, REL-11's error boundary caught it, and the user was told the microphone
 * was unavailable — a mic that was in fact perfectly fine. Every platform call now goes
 * through [onMain], so this no longer depends on where the flow is collected from.
 */
@Singleton
class OnDeviceSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Run [block] on the main looper. Inline when already there — which is the normal case
     * for the recognizer's own callbacks, since the platform delivers them on the looper the
     * recognizer was created on.
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun listen(): Flow<SpeechEvent> {
        if (!isAvailable()) return flowOf(SpeechEvent("", ""))

        return callbackFlow {
            val recognizer = AtomicReference<SpeechRecognizer?>(null)
            // Cleared by awaitClose from the collecting coroutine and read on the main
            // thread, hence atomic rather than a plain captured var.
            val running = AtomicBoolean(true)
            val consecutiveFailures = AtomicInteger(0)

            fun startListening() = onMain {
                if (!running.get()) return@onMain
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                runCatching { recognizer.get()?.startListening(intent) }
                    .onFailure { Log.w(TAG, "startListening failed: $it") }
            }

            /**
             * CAP-16: a recoverable error restarts on a capped exponential backoff instead
             * of immediately. The old code restarted only on three error codes and did so
             * with no delay, so a persistent one (`ERROR_RECOGNIZER_BUSY` while another app
             * holds the recognizer) span a restart loop as fast as the platform could
             * reject it, and every other code left the lane silently dead forever.
             */
            fun restartAfterFailure(error: Int) {
                val attempt = consecutiveFailures.incrementAndGet()
                val delay = retryDelayMs(attempt)
                Log.w(TAG, "recognizer error $error (attempt $attempt), retrying in ${delay}ms")
                mainHandler.postDelayed({ if (running.get()) startListening() }, delay)
            }

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    if (!running.get()) return
                    when (error) {
                        // Not failures at all: the ordinary shape of someone pausing between
                        // sentences. Restart straight away and forget any earlier backoff.
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        -> {
                            consecutiveFailures.set(0)
                            startListening()
                        }

                        // Nothing this app can do will ever fix these, so retrying only
                        // burns battery. The session stays alive for typed notes. CAP-21:
                        // this used to only log — the capture screen kept showing "Listening…"
                        // with nothing ever going to arrive again.
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        -> {
                            Log.w(TAG, "recognizer permanently unavailable (error $error)")
                            trySend(SpeechEvent("", "", recognizerDead = true))
                        }

                        else -> restartAfterFailure(error)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (text.isNotBlank()) {
                        consecutiveFailures.set(0)
                        trySend(SpeechEvent(finalizedUtterance = text, partialText = ""))
                    }
                    startListening()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        trySend(SpeechEvent(finalizedUtterance = "", partialText = partial))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }

            onMain {
                if (!running.get()) return@onMain
                val created = runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }.getOrElse {
                    Log.w(TAG, "could not create the on-device recognizer: $it")
                    close(it)
                    return@onMain
                }
                recognizer.set(created)
                created.setRecognitionListener(listener)
                startListening()
            }

            awaitClose {
                running.set(false)
                // Teardown is main-thread-only too (`cancel` checks; `destroy` posts). This
                // may already be off the main thread — awaitClose runs wherever the collector
                // cancelled from — which is exactly the bug this class just stopped having.
                onMain {
                    mainHandler.removeCallbacksAndMessages(null)
                    recognizer.getAndSet(null)?.let {
                        runCatching { it.cancel() }
                        runCatching { it.destroy() }
                    }
                }
            }
        }
            // CAP-16: the default callbackFlow buffer is 64, and `trySend` drops silently
            // when it is full. Dropping a finalized utterance is losing words the user
            // actually said, which is the one failure this app must never have. Partials are
            // disposable, but they share the channel, so the channel is unbounded instead.
            .buffer(Channel.UNLIMITED)
    }

    internal companion object {
        private const val TAG = "TrailMixSpeech"
        private const val BASE_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 8_000L

        /**
         * CAP-16 backoff: 250ms doubling to a 8s ceiling, for a 1-based attempt count.
         * Pure and hoisted out because it is the only part of this class a JVM test can
         * reach — everything else is platform glue that the android.jar stubs cannot run.
         */
        fun retryDelayMs(attempt: Int): Long {
            if (attempt <= 1) return BASE_RETRY_MS
            // Doubling in a loop rather than `shl (attempt - 1)`: Kotlin's `shl` uses only
            // the low six bits of the shift count, so a long-running failure would silently
            // wrap back around to a near-zero delay — the exact loop this is here to prevent.
            var delay = BASE_RETRY_MS
            repeat(attempt - 1) {
                if (delay >= MAX_RETRY_MS) return MAX_RETRY_MS
                delay *= 2
            }
            return delay.coerceAtMost(MAX_RETRY_MS)
        }
    }
}
