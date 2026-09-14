package com.trailmix.app.data.speech

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
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
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    /**
     * AI-01: non-null only when the user has turned on speaker diarization — set by
     * [CaptureSessionManager] before [start]. Every other consumer of this pipe reads and
     * discards; this is the one exception that retains a copy, because whole-session
     * diarization (a [SpeakerDiarizer] call) has no streaming API in either backend this app
     * has used (Picovoice Falcon, then sherpa-onnx's `OfflineSpeakerDiarization`) — only a
     * single whole-recording call.
     */
    @Volatile private var audioSink: AudioRetentionBuffer? = null

    fun setAudioSink(sink: AudioRetentionBuffer?) {
        audioSink = sink
    }

    /**
     * REL-12: the read end of the PCM pipe, retained rather than handed away and forgotten.
     *
     * It used to be a local in [start] that was returned and never referenced again, which
     * left the pipeline unable to guarantee its own shutdown: the mic pump blocks in
     * `out.write()` once the pipe's ~64 KiB (about two seconds of 16 kHz mono) fills, and the
     * only thing that can unblock it is the reader draining or the read end closing. On every
     * abandon path — pause, cancel — the recognizer has already stopped draining, so the pump
     * was relying on ML Kit closing an fd we had given away. [release] now closes it directly.
     */
    private var readSide: ParcelFileDescriptor? = null

    /**
     * Peak |amplitude| seen on the device-audio lane since the last read;
     * -1 when the lane isn't attached. Lets the UI tell the user when the
     * playing app is delivering silence (opted out of capture / voice call).
     */
    // CAP-20: was a @Volatile Int with a separate read-then-write reset — @Volatile only
    // guarantees visibility, not atomicity, so a pump-thread bump landing between this call's
    // read and its write-back-to-zero was silently clobbered a tick early. AtomicInteger makes
    // both the "bump to the new peak" and "read and reset" operations genuinely indivisible.
    private val playbackPeak = AtomicInteger(-1)

    fun readAndResetPlaybackPeak(): Int = playbackPeak.getAndUpdate { v -> if (v >= 0) 0 else v }

    /**
     * Starts the mic pump and returns the read end of the PCM pipe.
     * The caller must hold RECORD_AUDIO.
     */
    @SuppressLint("MissingPermission")
    fun start(preferredDevice: AudioDeviceInfo?): ParcelFileDescriptor {
        check(!running) { "pipeline already running" }
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        this.readSide = readSide
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
        attachAudioEffects(record.audioSessionId)

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
                    audioSink?.append(mic, n)
                    out.write(Pcm.toLittleEndianBytes(mic, n))
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "mic pump ended: $e")
            } finally {
                runCatching { out.flush() }
                runCatching { out.close() } // EOF → recognizer finalizes
                // REL-12: this thread owns the recorder's lifetime, exactly like the playback
                // pump below. stop() used to release it after a bounded join(1s), so a join
                // that timed out freed the native recorder while this thread could still be
                // inside record.read() — a use-after-free in native code, from a path that
                // times out precisely when the pump is wedged.
                runCatching { record.stop() }
                runCatching { record.release() }
                releaseAudioEffects()
            }
        }
        return readSide
    }

    /**
     * CAP-23: best-effort noise suppression + automatic gain ahead of the recognizer —
     * Krisp's on-device-cleanup value prop, done with a platform [android.media.audiofx]
     * effect instead of a new ML dependency. `isAvailable()` reports actual HAL/OEM
     * support, not SDK level — either effect (or both) can be absent on a given device,
     * and capture must work identically without them, so failure here is always silent.
     */
    private fun attachAudioEffects(sessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId) }
                .onFailure { Log.w(TAG, "NoiseSuppressor unavailable: $it") }
                .getOrNull()
                ?.apply { enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = runCatching { AutomaticGainControl.create(sessionId) }
                .onFailure { Log.w(TAG, "AutomaticGainControl unavailable: $it") }
                .getOrNull()
                ?.apply { enabled = true }
        }
    }

    private fun releaseAudioEffects() {
        noiseSuppressor?.let { runCatching { it.release() } }
        noiseSuppressor = null
        gainControl?.let { runCatching { it.release() } }
        gainControl = null
    }

    /**
     * REL-11: release both ends of a pipe whose pump thread will never run. Closing the
     * wrapping stream closes the write-side fd, so the two are not closed separately — that
     * would be a double close. Leaving these open leaks two file descriptors per failed
     * start attempt, and a user retrying a capture against a busy mic retries often.
     */
    private fun abandonPipe(readSide: ParcelFileDescriptor, out: OutputStream) {
        writeSide = null
        this.readSide = null
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
        playbackPeak.set(0)
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
                    playbackPeak.getAndUpdate { v -> maxOf(v, peak) }
                    // CAP-20: push's false return (ring full, chunk dropped) went unchecked —
                    // device-audio dropout was silently invisible. This is real signal loss
                    // (unlike low peak, which can legitimately mean the captured app is quiet),
                    // so it's worth its own log line rather than folding into the silence hint.
                    if (!playbackRing.push(at16k, at16k.size)) {
                        Log.w(TAG, "device-audio ring buffer full, dropped a chunk")
                    }
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
        val thread = playbackThread
        playbackRecord = null
        playbackPeak.set(-1)
        runCatching { record.stop() } // pump thread sees the swap and releases
        // CAP-20: nulling playbackThread here without waiting for it meant a fast
        // detach-then-reattach could start a second pump before this one noticed
        // `playbackRecord !== record` and exited — both threads pushing into the same
        // playbackRing at once. The pump still owns releasing its own record in its own
        // finally regardless of whether this join times out (REL-12's rule); this join only
        // closes the reattach race, it isn't what makes cleanup happen.
        runCatching { thread?.join(PUMP_JOIN_MS) }
        playbackThread = null
    }

    /**
     * End of input: stop the mic and close the write end, which signals EOF so the
     * recognizer emits its remaining final text and completes.
     *
     * This deliberately leaves the read end open — the recognizer is still draining the
     * last couple of seconds of buffered audio, and closing it here would truncate the
     * final utterance of every capture. Use [release] once that drain is over.
     */
    fun stop() = shutdown(closeReadSide = false)

    /**
     * REL-12: final teardown — after this, nothing is left holding the mic or the pipe,
     * whatever state the session was in.
     *
     * The distinction from [stop] is which side of the pipe is still wanted. [stop] is the
     * graceful EOF that lets the recognizer flush its tail. [release] is the abandon path
     * (the flow completed, or was cancelled by pause/discard), where nothing will ever drain
     * the pipe again — so the read end is closed *first*, which turns a pump thread blocked
     * in `out.write()` into an immediate EPIPE instead of a thread parked forever on a pipe
     * with no reader. Without it the join below could only ever time out, and the session
     * leaked a thread, two fds and the pipe buffer on every cancelled capture.
     */
    fun release() = shutdown(closeReadSide = true)

    private fun shutdown(closeReadSide: Boolean) {
        if (closeReadSide) {
            readSide?.let { runCatching { it.close() } }
            readSide = null
        }
        if (!running) return
        running = false
        detachPlayback()
        val record = micRecord
        micRecord = null
        // Unblock a pending read(). The pump thread — not this one — releases the recorder,
        // so a join that times out can no longer free it mid-read.
        record?.let { runCatching { it.stop() } }
        micThread?.join(PUMP_JOIN_MS)
        micThread = null
        writeSide = null
    }

    private companion object {
        const val TAG = "TrailMixAudio"

        /**
         * How long [shutdown] waits for the mic pump to finish. It is a backstop, not the
         * mechanism: stopping the recorder unblocks a pending `read()` and closing the read
         * end unblocks a pending `write()`, so the pump normally exits in well under this.
         */
        const val PUMP_JOIN_MS = 1_000L
    }
}
