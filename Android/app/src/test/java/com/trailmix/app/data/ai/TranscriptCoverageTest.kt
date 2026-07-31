package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-05: whole-session coverage — the transcript must never be read only from the front. */
class TranscriptCoverageTest {

    private fun line(label: String, text: String) = TranscriptLine(label, text)

    /** A talk of [minutes] minutes, one distinct line per minute. */
    private fun talk(minutes: Int) = (0 until minutes).map {
        line("$it:00", "Minute $it discusses subject$it and how subject$it affects throughput.")
    }

    @Test
    fun `parses mm ss and h mm ss labels, rejects junk`() {
        assertEquals(0, TranscriptCoverage.parseLabelSeconds("0:00"))
        assertEquals(754, TranscriptCoverage.parseLabelSeconds("12:34"))
        assertEquals(3723, TranscriptCoverage.parseLabelSeconds("1:02:03"))
        assertNull(TranscriptCoverage.parseLabelSeconds(""))
        assertNull(TranscriptCoverage.parseLabelSeconds(null))
        assertNull(TranscriptCoverage.parseLabelSeconds("abc"))
        assertNull(TranscriptCoverage.parseLabelSeconds("12"))
        assertNull(TranscriptCoverage.parseLabelSeconds("1:2:3:4"))
    }

    @Test
    fun `formats seconds back, adding hours only when needed`() {
        assertEquals("0:00", TranscriptCoverage.formatSeconds(0))
        assertEquals("12:34", TranscriptCoverage.formatSeconds(754))
        assertEquals("1:02:03", TranscriptCoverage.formatSeconds(3723))
    }

    @Test
    fun `a short session stays a single unlabelled window`() {
        val windows = TranscriptCoverage.windows(talk(5))
        assertEquals(1, windows.size)
        assertEquals("", windows.single().rangeLabel)
        assertEquals(5, windows.single().lines.size)
    }

    @Test
    fun `unlabelled lines never crash and collapse to one window`() {
        val windows = TranscriptCoverage.windows(
            listOf(line("", "First thing."), line("", "Second thing.")),
        )
        assertEquals(1, windows.size)
        assertEquals("", windows.single().rangeLabel)
    }

    @Test
    fun `a long talk splits into time-ranged windows spanning the whole session`() {
        val windows = TranscriptCoverage.windows(talk(45))
        assertTrue("expected multiple windows, got ${windows.size}", windows.size > 1)
        assertTrue(windows.size <= TranscriptCoverage.MAX_WINDOWS)
        // Every line is accounted for — windowing partitions, it never drops.
        assertEquals(45, windows.sumOf { it.lines.size })
        // Coverage reaches the end of the talk, which is the whole point of the change.
        assertTrue(windows.last().lines.any { it.label == "44:00" })
        assertTrue(windows.first().rangeLabel.startsWith("0:00"))
    }

    @Test
    fun `even sampling keeps content from the end, not just the front`() {
        val lines = talk(60)
        val sampled = TranscriptCoverage.evenSampleLines(lines, maxChars = 800)
        val text = sampled.joinToString(" ") { it.text }
        assertTrue("sample must fit the budget", text.length <= 800 + 200)
        assertTrue("must include late material", sampled.any { it.label.startsWith("5") })
        assertTrue("must include early material", sampled.any { it.label == "0:00" })
        // The naive `.take(maxChars)` this replaced would have stopped in the first minutes.
        assertTrue(sampled.size < lines.size)
    }

    @Test
    fun `sampling is a no-op when everything already fits`() {
        val lines = talk(3)
        assertEquals(lines, TranscriptCoverage.evenSampleLines(lines, maxChars = 10_000))
    }

    @Test
    fun `chunking splits consecutively and covers every line`() {
        val lines = talk(30)
        val chunks = TranscriptCoverage.chunks(lines, maxChars = 400)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.size <= TranscriptCoverage.MAX_CHUNKS)
        assertEquals(lines.first().label, chunks.first().first().label)
        assertEquals(lines.last().label, chunks.last().last().label)
    }

    @Test
    fun `an over-long session is bounded to MAX_CHUNKS but still spans end to end`() {
        val lines = talk(180)
        val chunks = TranscriptCoverage.chunks(lines, maxChars = 500)
        assertEquals(TranscriptCoverage.MAX_CHUNKS, chunks.size)
        // Bounded model calls, yet the last chunk still comes from the last stretch of talk.
        val lastLabel = chunks.last().last().label
        assertTrue("expected a late timestamp, got $lastLabel", lastLabel.substringBefore(':').toInt() > 150)
    }

    @Test
    fun `empty and blank input is handled without throwing`() {
        assertTrue(TranscriptCoverage.windows(emptyList()).isEmpty())
        assertTrue(TranscriptCoverage.chunks(emptyList(), 100).isEmpty())
        assertTrue(TranscriptCoverage.evenSampleLines(emptyList(), 100).isEmpty())
        assertTrue(TranscriptCoverage.windows(listOf(line("0:01", "   "))).isEmpty())
    }
}
