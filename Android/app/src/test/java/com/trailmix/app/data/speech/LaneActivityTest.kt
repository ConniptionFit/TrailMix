package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAP-31: the Me/Them lane-dominance decision and its bounded timeline. */
class LaneActivityTest {

    private fun activity(capacity: Int = LaneActivity.DEFAULT_CAPACITY) =
        LaneActivity(clock = { 0L }, capacity = capacity)

    /** Feed [seconds] of 100 ms buckets starting at [fromMs]. */
    private fun LaneActivity.feed(fromMs: Long, seconds: Int, me: Float, them: Float, attached: Boolean = true) {
        for (i in 0 until seconds * 10) recordAt(fromMs + i * 100L, me, them, attached)
    }

    @Test
    fun `loud playback with quiet mic is Them`() {
        val a = activity().apply { feed(0, 5, me = 100f, them = 2_000f) }
        assertEquals(SpeechSource.THEM, a.dominantLane(0, 4_900))
    }

    @Test
    fun `loud mic with silent playback is Me`() {
        val a = activity().apply { feed(0, 5, me = 2_000f, them = 0f) }
        assertEquals(SpeechSource.ME, a.dominantLane(0, 4_900))
    }

    @Test
    fun `crosstalk inside the dominance ratio is ambiguous`() {
        val a = activity().apply { feed(0, 5, me = 1_500f, them = 1_000f) }
        assertNull(a.dominantLane(0, 4_900))
    }

    @Test
    fun `bleed of the loudspeaker into the mic does not flip Them to Me`() {
        val a = activity().apply { feed(0, 5, me = 600f, them = 2_000f) }
        assertEquals(SpeechSource.THEM, a.dominantLane(0, 4_900))
    }

    @Test
    fun `a quiet window is null`() {
        val a = activity().apply { feed(0, 5, me = 40f, them = 10f) }
        assertNull(a.dominantLane(0, 4_900))
    }

    @Test
    fun `mic-only capture never labels anything`() {
        val a = activity().apply { feed(0, 5, me = 3_000f, them = 0f, attached = false) }
        assertNull(a.dominantLane(0, 4_900))
    }

    @Test
    fun `no data and inverted spans are null`() {
        val a = activity()
        assertNull(a.dominantLane(0, 1_000))
        a.feed(0, 2, 2_000f, 0f)
        assertNull(a.dominantLane(5_000, 1_000))
        assertNull(a.dominantLane(60_000, 70_000))
    }

    @Test
    fun `only the queried span counts`() {
        val a = activity().apply {
            feed(0, 5, me = 2_000f, them = 0f)
            feed(5_000, 5, me = 100f, them = 2_000f)
        }
        assertEquals(SpeechSource.ME, a.dominantLane(0, 4_900))
        assertEquals(SpeechSource.THEM, a.dominantLane(5_000, 9_900))
    }

    @Test
    fun `memory is bounded and old buckets drop out`() {
        val a = activity(capacity = 100) // 10 s window
        a.feed(0, 60, me = 2_000f, them = 0f)
        assertTrue(a.size() <= 100)
        assertNull(a.dominantLane(0, 5_000)) // scrolled out
        assertEquals(SpeechSource.ME, a.dominantLane(55_000, 59_900))
    }

    @Test
    fun `clear forgets everything`() {
        val a = activity().apply { feed(0, 5, me = 2_000f, them = 0f) }
        a.clear()
        assertEquals(0, a.size())
        assertNull(a.dominantLane(0, 4_900))
    }

    @Test
    fun `line attribution starts the span at the previous line`() {
        val a = activity().apply {
            feed(0, 10, me = 2_000f, them = 0f)
            feed(10_000, 5, me = 100f, them = 2_000f)
        }
        // Previous line ended at 10 s, so only the Them stretch counts.
        assertEquals(SpeechSource.THEM, a.sourceForLine(prevLineMs = 10_000, nowMs = 14_900))
        // No previous line: span is capped at 30 s back, clamped to the start of the session.
        assertEquals(SpeechSource.ME, a.sourceForLine(prevLineMs = -1, nowMs = 9_900))
    }

    @Test
    fun `rms of a constant signal is its magnitude`() {
        assertEquals(1_000f, LaneActivity.rms(ShortArray(160) { 1_000 }, 160), 0.5f)
        assertEquals(0f, LaneActivity.rms(ShortArray(4), 0), 0f)
    }

    @Test
    fun `record computes energy from raw chunks`() {
        var now = 0L
        val a = LaneActivity(clock = { now })
        val mic = ShortArray(160) { 100 }
        val pb = ShortArray(160) { 5_000 }
        repeat(30) {
            now = it * 100L
            a.record(mic, 160, pb, 160, playbackAttached = true)
        }
        assertEquals(SpeechSource.THEM, a.dominantLane(0, 2_900))
    }

    @Test
    fun `levelOf is monotonic bounded and lifts quiet speech above a linear map`() {
        assertEquals(0f, LaneActivity.levelOf(0f), 0f)
        assertEquals(1f, LaneActivity.levelOf(LaneActivity.LEVEL_FULL_SCALE_RMS), 1e-6f)
        assertEquals(1f, LaneActivity.levelOf(1e9f), 0f)
        assertEquals(0f, LaneActivity.levelOf(-5f), 0f)
        assertTrue(LaneActivity.levelOf(300f) > 300f / LaneActivity.LEVEL_FULL_SCALE_RMS)
        assertTrue(LaneActivity.levelOf(900f) > LaneActivity.levelOf(300f))
    }

    @Test
    fun `recentLevel is zero before any chunk and tracks the louder lane afterwards`() {
        val a = activity()
        assertEquals(0f, a.recentLevel(), 0f)
        a.recordAt(0, 1_500f, 0f, false)
        assertEquals(LaneActivity.levelOf(1_500f), a.recentLevel(), 1e-6f)
        a.recordAt(100, 200f, 3_000f, true)
        assertEquals(LaneActivity.levelOf(3_000f), a.recentLevel(), 1e-6f)
    }
}
