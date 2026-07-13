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
        record.preferredDevice = preferredDevice
        micRecord = record
        running = true
        record.startRecording()

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
        record.startRecording()
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
