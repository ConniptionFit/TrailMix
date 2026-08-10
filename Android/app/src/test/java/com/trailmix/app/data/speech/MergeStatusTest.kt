package com.trailmix.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REL-10. The label is what a user stares at for minutes while a keynote merges, and the
 * notification and the Capture screen both render it — so the states it can be in, and the
 * fact that it never sits still, are worth pinning.
 */
class MergeStatusTest {

    @Test
    fun `a merge that has not been chunked yet is indeterminate`() {
        val status = MergeStatus("Identity governance keynote")
        assertEquals(MergeStatus.MERGING, status.label())
        assertTrue(status.indeterminate)
    }

    @Test
    fun `a short session fitting one model call stays indeterminate`() {
        // One chunk is a single generateContent call — there is no meaningful N-of-M to
        // show, and "Summarizing 1 of 1" would be noise.
        val status = MergeStatus("Standup", chunksDone = 0, chunksTotal = 1)
        assertEquals(MergeStatus.MERGING, status.label())
        assertTrue(status.indeterminate)
    }

    @Test
    fun `chunk progress counts the chunk being worked on, not the ones finished`() {
        val total = 6
        // Nothing done yet still reads as "1 of 6" — work has started on the first chunk.
        assertEquals("Summarizing 1 of 6…", MergeStatus("Keynote", 0, total).label())
        assertEquals("Summarizing 2 of 6…", MergeStatus("Keynote", 1, total).label())
        assertEquals("Summarizing 6 of 6…", MergeStatus("Keynote", 5, total).label())
        assertFalse(MergeStatus("Keynote", 1, total).indeterminate)
    }

    @Test
    fun `the last chunk finishing hands over to the structuring pass`() {
        // generateStructuredSummary makes its own model call after the chunks are condensed.
        // Sitting on "6 of 6" through that would be the frozen line the whole feature exists
        // to avoid.
        assertEquals(MergeStatus.WRITING, MergeStatus("Keynote", 6, 6).label())
    }

    @Test
    fun `the label always moves across a whole keynote-scale merge`() {
        // The property that matters: no two consecutive states render identically, so the
        // user never sees the same string twice in a row and conclude it has hung.
        val total = 6
        val labels = (0..total).map { MergeStatus("Keynote", it, total).label() }
        labels.zipWithNext().forEach { (a, b) ->
            assertTrue("progress label repeated: $a", a != b)
        }
    }

    @Test
    fun `progress never claims more chunks are done than exist`() {
        // A late callback arriving after the count shrank must not produce "7 of 6".
        assertEquals(MergeStatus.WRITING, MergeStatus("Keynote", 9, 6).label())
    }

    @Test
    fun `the title is carried through untouched`() {
        // The notification's content title — it is the note being written into, and
        // copy() during progress updates must not disturb it.
        val started = MergeStatus("Front of House Architecture")
        val progressed = started.copy(chunksDone = 2, chunksTotal = 5)
        assertEquals("Front of House Architecture", progressed.title)
    }
}
