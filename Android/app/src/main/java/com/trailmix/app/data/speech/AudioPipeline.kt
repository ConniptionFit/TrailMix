package com.trailmix.app.data.speech

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * The app-owned audio front end: a mic [AudioRecord] (with a selectable input
 * device) plus an optional device-audio lane via [AudioPlaybackCapture], mixed
 * into one 16 kHz mono PCM stream and written to an in-memory pipe that the
 * on-device recognizer reads.
 *
 * Privacy: audio only ever flows through RAM — the pipe is a kernel buffer,
 * never a file. Owning the AudioRecord (instead of the system SpeechRecognizer)
 * is also what removes every recognizer start/stop chime: no system recognition
 * session ever runs.
 *
 * Note on meetings: Android excludes voice-call playback (USAGE_VOICE_COMMUNICATION)
 * from playback capture and lets apps opt out entirely, so the device-audio lane
 * hears media apps (YouTube, podcasts, videos) but not the far end of a
 * Teams/Zoom call. That is an OS policy boundary, not a setting.
 */
/**
 * REL-11: the mic could not be opened — almost always because something else holds it
 * (an in-progress call, another recorder, the assistant), occasionally because the device
 * refused the buffer size. Typed as its own exception so [CaptureEngine] can treat it as a
 * *recoverable* start failure rather than letting it escape as a raw `IllegalStateException`.
 */
class AudioUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class AudioPipeline {

    private val chunkFrames = Pcm.SAMPLE_RATE / 10 // 100 ms of 16 kHz mono
    private val playbackRing = ShortRingBuffer(Pcm.SAMPLE_RATE * 4) // 4 s headroom

    @Volatile private var running = false
    private var micRecord: AudioRecord? = null
    @Volatile private var playbackRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private var playbackThread: Thread? = null
    private var writeSide: ParcelFileDescriptor? = null

    /**
     * Peak |amplitude| seen on the device-audio lane since the last read;
     * -1 when the lane isn't attached. Lets the UI tell the user when the
     * playing app is delivering silence (opted out of capture / voice call).
     */
    @Volatile private var playbackPeak = -1

    fun readAndResetPlaybackPeak(): Int {
        val v = playbackPeak
        if (v >= 0) playbackPeak = 0
        return v
    }

    /**
     * Starts the mic pump and returns the read end of the PCM pipe.
     * The caller must hold RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    fun start(preferredDevice: AudioDeviceInfo?): ParcelFileDescriptor {
        check(!running) { "pipeline already running" }
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        writeSide = pipe[1]
        val out: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

        val minBuf = AudioRecord.getMinBufferSize(
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Pcm.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 4, chunkFrames * 2 * 4),
        )
        // REL-11: the mic lane must be checked exactly like the device-audio lane below it —
        // it never was, and that asymmetry was a crash. `AudioRecord` does NOT throw when it
        // fails to acquire the mic: it constructs in STATE_UNINITIALIZED, and the throw comes
        // later from startRecording(). That escaped through CaptureEngine.begin() into an
        // unguarded `scope.launch`, which on Android means the process dies. The trigger is
        // ordinary — anything else holding the mic (a call, another recorder, the assistant),
        // and "start a capture during a call" is a scenario this app explicitly supports.
        //
        // Cleanup on failure matters as much as the check: `running` was set before
        // startRecording(), so a throw left it true forever and every later start() hit
        // `check(!running)` — the pipeline was bricked for the rest of the process, with the
        // AudioRecord and both pipe fds leaked.
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            abandonPipe(readSide, out)
            throw AudioUnavailableException("microphone unavailable (uninitialized recorder)")
        }
        record.preferredDevice = preferredDevice
        micRecord = record
        running = true
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            running = false
            micRecord = null
            runCatching { record.release() }
            abandonPipe(readSide, out)
            throw AudioUnavailableException("microphone could not be started", e)
        }

        micThread = thread(name = "trailmix-mic-pump") {
            val mic = ShortArray(chunkFrames)
            val playback = ShortArray(chunkFrames)
            try {
                while (running) {
                    val n = record.read(mic, 0, mic.size)
                    if (n <= 0) {
                        if (!running) break
                        Thread.sleep(20)
                        continue
                    }
                    val fromPlayback = playbackRing.pop(playback, n)
                    if (fromPlayback > 0) Pcm.mixInto(mic, playback, fromPlayback)
                    out.write(Pcm.toLittleEndianBytes(mic, n))
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "mic pump ended: $e")
            } finally {
                runCatching { out.flush() }
                runCatching { out.close() } // EOF → recognizer finalizes
            }
        }
        return readSide
    }

    /**
     * REL-11: release both ends of a pipe whose pump thread will never run. Closing the
     * wrapping stream closes the write-side fd, so the two are not closed separately — that
     * would be a double close. Leaving these open leaks two file descriptors per failed
     * start attempt, and a user retrying a capture against a busy mic retries often.
     */
    private fun abandonPipe(readSide: ParcelFileDescriptor, out: OutputStream) {
        writeSide = null
        runCatching { out.close() }
        runCatching { readSide.close() }
    }

    /** Reroute the mic mid-session; null returns to automatic routing. */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        micRecord?.preferredDevice = device
    }

    /**
     * Attach the device-audio lane. Captures what other apps play (media/game/
     * unknown usages — the OS excludes voice-call audio) at 48 kHz stereo, then
     * downmixes and decimates into the mic clock via the ring buffer.
     */
    @SuppressLint("MissingPermission")
    fun attachPlayback(projection: MediaProjection): Boolean {
        if (!running || playbackRecord != null) return false
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Pcm.PLAYBACK_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(Pcm.PLAYBACK_SAMPLE_RATE * 2 * 2) // 1 s stereo
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "playback capture unavailable: $e")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        playbackRecord = record
        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "playback capture start failed: $e")
            playbackRecord = null
            record.release()
            return false
        }
        playbackPeak = 0
        playbackThread = thread(name = "trailmix-playback-pump") {
            // 100 ms of 48 kHz stereo per read.
            val raw = ShortArray(Pcm.PLAYBACK_SAMPLE_RATE / 10 * 2)
            try {
                while (running && playbackRecord === record) {
                    val n = record.read(raw, 0, raw.size)
                    if (n <= 0) {
                        if (!running) break
                        Thread.sleep(20)
                        continue
                    }
                    val mono = Pcm.downmixStereoToMono(raw, n)
                    val at16k = Pcm.decimate3(mono, mono.size)
                    val peak = Pcm.peak(at16k, at16k.size)
                    if (peak > playbackPeak) playbackPeak = peak
                    playbackRing.push(at16k, at16k.size)
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "playback pump ended: $e")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }
        return true
    }

    /** Detach the device-audio lane; the mic lane keeps running. */
    fun detachPlayback() {
        val record = playbackRecord ?: return
        playbackRecord = null
        playbackPeak = -1
        runCatching { record.stop() } // pump thread sees the swap and releases
        playbackThread = null
    }

    /**
     * Stop everything and close the write end, which signals EOF so the
     * recognizer emits its remaining final text and completes.
     */
    fun stop() {
        if (!running) return
        running = false
        detachPlayback()
        micRecord?.let { runCatching { it.stop() } }
        micThread?.join(1_000)
        micRecord?.release()
        micRecord = null
        micThread = null
        writeSide = null
    }

    private companion object {
        const val TAG = "TrailMixAudio"
    }
}
