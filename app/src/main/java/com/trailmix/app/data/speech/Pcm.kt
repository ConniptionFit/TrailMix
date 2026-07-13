package com.trailmix.app.data.speech

/**
 * Small PCM helpers for the capture pipeline. Pure JVM code — unit tested
 * without an emulator.
 *
 * The recognizer consumes raw 16 kHz mono 16-bit PCM. Device-audio capture
 * arrives as 48 kHz stereo, so it is downmixed and decimated 3:1 before being
 * mixed into the mic stream.
 */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val PLAYBACK_SAMPLE_RATE = 48_000

    /** Average L/R pairs into mono. [samples] holds interleaved stereo. */
    fun downmixStereoToMono(samples: ShortArray, count: Int): ShortArray {
        val frames = count / 2
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            out[i] = ((samples[2 * i].toInt() + samples[2 * i + 1].toInt()) / 2).toShort()
        }
        return out
    }

    /** 48 kHz → 16 kHz: average each group of three samples (cheap low-pass). */
    fun decimate3(samples: ShortArray, count: Int): ShortArray {
        val outCount = count / 3
        val out = ShortArray(outCount)
        for (i in 0 until outCount) {
            val base = i * 3
            out[i] = (
                (samples[base].toInt() + samples[base + 1].toInt() + samples[base + 2].toInt()) / 3
                ).toShort()
        }
        return out
    }

    /** Saturating add of [src] into [dst] (first [count] samples). */
    fun mixInto(dst: ShortArray, src: ShortArray, count: Int) {
        for (i in 0 until count) {
            val sum = dst[i].toInt() + src[i].toInt()
            dst[i] = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Serialize the first [count] samples as little-endian 16-bit PCM bytes. */
    fun toLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/**
 * Fixed-capacity ring buffer for 16-bit samples, bridging the device-audio
 * pump (producer) and the mic-paced mixer (consumer). When full, the incoming
 * chunk is dropped — live transcription must never stall on backpressure.
 */
class ShortRingBuffer(private val capacity: Int) {
    private val buf = ShortArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun push(samples: ShortArray, count: Int): Boolean {
        if (count > capacity - size) return false
        var tail = (head + size) % capacity
        for (i in 0 until count) {
            buf[tail] = samples[i]
            tail = (tail + 1) % capacity
        }
        size += count
        return true
    }

    /** Pop up to [count] samples into [dest]; returns how many were written. */
    @Synchronized
    fun pop(dest: ShortArray, count: Int): Int {
        val n = minOf(count, size)
        for (i in 0 until n) {
            dest[i] = buf[head]
            head = (head + 1) % capacity
        }
        size -= n
        return n
    }

    @Synchronized
    fun availableToRead(): Int = size
}
