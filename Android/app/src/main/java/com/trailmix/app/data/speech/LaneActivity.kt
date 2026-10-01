package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import kotlin.math.sqrt

/**
 * CAP-31: a bounded, thread-safe timeline of per-chunk energy for the two capture lanes, so a
 * finalized transcript line can be attributed to "Me" or "Them".
 *
 * The pipeline mixes playback into the mic buffer before the single mixed stream reaches the
 * recognizer, so a line cannot tell on its own who said it. The mic pump therefore reports each
 * chunk's per-lane energy here *before* mixing; [dominantLane] later answers "which lane was
 * louder over this utterance's span". Pure JVM — no Android types, unit tested.
 *
 * Time base: [record] stamps each chunk with [clock], which [CaptureSessionManager] points at
 * the same capture-elapsed timeline transcript labels use (paused time excluded), so a line's
 * span lines up with the buckets without any conversion.
 *
 * Memory is fixed: a circular array of [capacity] buckets of [BUCKET_MS] each (default 100
 * minutes, ~1 MB). A bucket older than the window simply gets overwritten; queries for spans
 * that have scrolled out return null. Nothing is persisted — only the *decision* (an enum on
 * the transcript line) outlives the session.
 */
class LaneActivity(
    private val clock: () -> Long,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val stamp = LongArray(capacity) { -1L } // absolute bucket index held in each slot
    private val meRms = FloatArray(capacity)
    private val themRms = FloatArray(capacity)
    private val themLive = BooleanArray(capacity) // playback lane attached for this bucket

    /**
     * Record one chunk, *before* playback is mixed into [mic]. [playbackAttached] is whether the
     * device-audio lane is currently running at all (distinct from it being silent): it is what
     * lets [dominantLane] return null for mic-only captures instead of calling everything "Me".
     * [playbackCount] is how many playback samples were popped for this chunk (0 = silence).
     */
    fun record(
        mic: ShortArray,
        micCount: Int,
        playback: ShortArray,
        playbackCount: Int,
        playbackAttached: Boolean,
    ) = recordAt(
        clock(),
        rms(mic, micCount),
        if (playbackCount > 0) rms(playback, playbackCount) else 0f,
        playbackAttached,
    )

    /** Core of [record] with the energies already computed — the seam tests drive directly. */
    fun recordAt(nowMs: Long, micRms: Float, playbackRms: Float, playbackAttached: Boolean) {
        if (nowMs < 0) return
        val idx = nowMs / BUCKET_MS
        val slot = (idx % capacity).toInt()
        synchronized(lock) {
            if (stamp[slot] != idx) {
                stamp[slot] = idx
                meRms[slot] = 0f
                themRms[slot] = 0f
                themLive[slot] = false
            }
            if (micRms > meRms[slot]) meRms[slot] = micRms
            if (playbackRms > themRms[slot]) themRms[slot] = playbackRms
            if (playbackAttached) themLive[slot] = true
        }
    }

    /**
     * Attribute a just-finalized utterance. The recognizer reports an utterance only when it
     * ends, so its span is estimated as the time since the previous line ([prevLineMs], or
     * negative when there is none) capped at [MAX_UTTERANCE_MS] — a long gap before a line is
     * mostly silence or other lines, not one utterance.
     */
    fun sourceForLine(prevLineMs: Long, nowMs: Long): SpeechSource? {
        val from = if (prevLineMs < 0) nowMs - MAX_UTTERANCE_MS else maxOf(prevLineMs, nowMs - MAX_UTTERANCE_MS)
        return dominantLane(from, nowMs)
    }

    /** Drop everything — a new session must not inherit the previous one's timeline. */
    fun clear() = synchronized(lock) { stamp.fill(-1L) }

    /** Number of buckets currently holding data (bounded by [capacity]). */
    fun size(): Int = synchronized(lock) { stamp.count { it >= 0 } }

    /**
     * The lane that dominated `[fromMs, toMs]`, or null when the answer would be a guess:
     *  - the device-audio lane was never attached in the window (mic-only capture — leave the
     *    line unlabeled; diarization covers that case),
     *  - no data for the span (scrolled out of the window, or never recorded),
     *  - both lanes were near-silent ([QUIET_RMS]), or
     *  - neither lane clears [DOMINANCE_RATIO] over the other (crosstalk).
     *
     * Energy is the sum of per-bucket RMS per lane. THEM wins when it is at least
     * [DOMINANCE_RATIO] times ME and vice versa. The ratio is 2 (about 6 dB) because the mic
     * also hears the loudspeaker: during pure "Them" speech the mic lane is not zero, so a bare
     * "which is louder" would flip on bleed; demanding a clear 2x margin keeps the label
     * conservative, and a wrong "Them" is worse than none.
     */
    fun dominantLane(fromMs: Long, toMs: Long): SpeechSource? {
        if (toMs < fromMs) return null
        val first = maxOf(0L, fromMs) / BUCKET_MS
        val last = toMs / BUCKET_MS
        if (last - first >= capacity) return null // span longer than the window can hold
        var me = 0.0
        var them = 0.0
        var n = 0
        var anyThemLive = false
        synchronized(lock) {
            for (idx in first..last) {
                val slot = (idx % capacity).toInt()
                if (stamp[slot] != idx) continue
                n++
                me += meRms[slot]
                them += themRms[slot]
                if (themLive[slot]) anyThemLive = true
            }
        }
        if (n == 0 || !anyThemLive) return null
        if (maxOf(me, them) / n < QUIET_RMS) return null
        return when {
            them >= me * DOMINANCE_RATIO -> SpeechSource.THEM
            me >= them * DOMINANCE_RATIO -> SpeechSource.ME
            else -> null
        }
    }

    companion object {
        const val BUCKET_MS = 100L

        /** Longest span attributed to a single finalized line. */
        const val MAX_UTTERANCE_MS = 30_000L

        /** 100 minutes of 100 ms buckets: covers the 90-minute retention cap with headroom. */
        const val DEFAULT_CAPACITY = 60_000

        /** Required energy margin of the winning lane. See [dominantLane]. */
        const val DOMINANCE_RATIO = 2.0

        /** Mean per-bucket RMS (16-bit scale) below which the span is treated as silence. */
        const val QUIET_RMS = 150.0

        /** Root-mean-square of the first [count] samples. */
        fun rms(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var acc = 0.0
            for (i in 0 until count) {
                val s = samples[i].toDouble()
                acc += s * s
            }
            return sqrt(acc / count).toFloat()
        }
    }
}
