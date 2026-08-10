package com.trailmix.app.data.speech

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** Which transcription backend a capture session is using. */
enum class EngineKind {
    /** App-owned pipeline + Gemini (AICore) recognition: silent, mic-selectable, device audio capable. */
    MLKIT,

    /** System SpeechRecognizer fallback: mic only, chimes muted best-effort. */
    LEGACY,

    /** No on-device recognition available at all (typed notes still work). */
    NONE,
}

/**
 * Orchestrates a capture session: picks the best available backend, owns the
 * [AudioPipeline] when the ML Kit recognizer is available, and manages the
 * MediaProjection used for the device-audio lane.
 */
@Singleton
class CaptureEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val legacy: OnDeviceSpeechRecognizer,
    private val transcriber: MlKitTranscriber,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _kind = MutableStateFlow(EngineKind.NONE)
    val kind: StateFlow<EngineKind> = _kind.asStateFlow()

    private val _deviceAudioActive = MutableStateFlow(false)
    val deviceAudioActive: StateFlow<Boolean> = _deviceAudioActive.asStateFlow()

    private val _asrDownloading = MutableStateFlow(false)
    val asrDownloading: StateFlow<Boolean> = _asrDownloading.asStateFlow()

    private var pipeline: AudioPipeline? = null
    private var projection: MediaProjection? = null
    private val mutedStreams = mutableListOf<Int>()

    /**
     * Start a session and return the speech-event stream. Prefers the ML Kit
     * pipeline; if the model is merely not downloaded yet, kicks the download
     * in the background and falls back to the system recognizer for this
     * session (fail-soft — same rule as every AI feature in the app).
     */
    suspend fun begin(preferredDevice: AudioDeviceInfo?): Flow<SpeechEvent> {
        when (transcriber.status()) {
            FeatureStatus.AVAILABLE -> {
                val pipe = AudioPipeline()
                pipeline = pipe
                // REL-11: opening the mic is the one step here that routinely fails for
                // reasons outside the app — something else holds it. Previously that threw
                // straight through begin() into an unguarded coroutine and killed the
                // process. It is a start failure, not a fatal one, so it drops through the
                // same fail-soft ladder every other capability in this app uses.
                val pfd = try {
                    pipe.start(preferredDevice)
                } catch (e: AudioUnavailableException) {
                    Log.w(TAG, "mic pipeline unavailable, falling back: $e")
                    pipeline = null
                    null
                }
                if (pfd != null) {
                    _kind.value = EngineKind.MLKIT
                    return transcriber.transcribe(pfd)
                        .onCompletion { teardownPipeline() }
                }
            }

            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                _asrDownloading.value = true
                scope.launch {
                    transcriber.download()
                    _asrDownloading.value = false
                }
            }
        }
        return if (legacy.isAvailable()) {
            _kind.value = EngineKind.LEGACY
            legacy.listen()
                .onStart { muteSystemChimes() }
                .onCompletion { restoreSystemChimes() }
        } else {
            _kind.value = EngineKind.NONE
            emptyFlow()
        }
    }

    /**
     * Signal end-of-input. In pipeline mode this closes the PCM stream so the
     * recognizer flushes its last utterance and the flow completes on its own;
     * the legacy flow is simply cancelled by its collector.
     */
    fun endInput() {
        pipeline?.stop()
        restoreSystemChimes()
    }

    /** Reroute the mic mid-session (pipeline mode only). Null = automatic. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        pipeline?.setPreferredDevice(device)
    }

    val deviceAudioSupported: Boolean
        get() = pipeline != null

    /** Peak device-audio amplitude since last poll; -1 when lane detached. */
    fun readAndResetPlaybackPeak(): Int = pipeline?.readAndResetPlaybackPeak() ?: -1

    /**
     * Attach device-audio capture from a fresh MediaProjection consent result.
     * Must only be called while the capture foreground service is running with
     * the mediaProjection type (Android 14+ requirement).
     */
    fun attachDeviceAudio(resultCode: Int, resultData: Intent): Boolean {
        val pipe = pipeline ?: return false
        val manager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.w(TAG, "getMediaProjection failed: $e")
            null
        } ?: return false
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = detachDeviceAudio()
            },
            Handler(Looper.getMainLooper()),
        )
        return if (pipe.attachPlayback(mp)) {
            projection = mp
            _deviceAudioActive.value = true
            true
        } else {
            runCatching { mp.stop() }
            false
        }
    }

    fun detachDeviceAudio() {
        pipeline?.detachPlayback()
        projection?.let { runCatching { it.stop() } }
        projection = null
        _deviceAudioActive.value = false
    }

    /** Distinct input devices worth offering in the capture menu. */
    fun availableInputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in SELECTABLE_INPUT_TYPES }
            .distinctBy { it.type to it.productName.toString() }

    private fun teardownPipeline() {
        detachDeviceAudio()
        // REL-12: release(), not stop(). This runs when the recognizer flow has completed or
        // been cancelled, so nothing will drain the PCM pipe again — the read end has to be
        // closed here or a pump thread blocked writing into it is never freed.
        pipeline?.release()
        pipeline = null
    }

    /**
     * Legacy mode only: the system recognizer plays start/stop chimes on every
     * utterance, and our continuous loop restarts constantly. Muting these
     * streams for the session is the only lever Android gives a third-party
     * app. Each stream is best-effort — Do Not Disturb can veto some of them.
     */
    private fun muteSystemChimes() {
        if (mutedStreams.isNotEmpty()) return
        for (stream in CHIME_STREAMS) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams += stream
            } catch (e: Exception) {
                Log.w(TAG, "could not mute stream $stream: $e")
            }
        }
    }

    private fun restoreSystemChimes() {
        for (stream in mutedStreams) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                Log.w(TAG, "could not unmute stream $stream: $e")
            }
        }
        mutedStreams.clear()
    }

    private companion object {
        const val TAG = "TrailMixEngine"
        val CHIME_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
        )
        val SELECTABLE_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
