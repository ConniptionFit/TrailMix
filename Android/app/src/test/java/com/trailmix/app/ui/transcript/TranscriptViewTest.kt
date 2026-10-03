package com.trailmix.app.ui.transcript

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptViewTest {

    private fun line(text: String, speaker: String? = null, source: SpeechSource? = null) =
        TranscriptLine("0:00", text, speakerLabel = speaker, speechSource = source)

    @Test
    fun `consecutive lines by one speaker share a header`() {
        val lines = listOf(
            line("a", "Speaker 1"),
            line("b", "Speaker 1"),
            line("c", "Speaker 2"),
            line("d", "Speaker 1"),
        )
        val items = buildTranscriptItems(lines, lines.indices.toList(), emptySet())
        val headers = items.filterIsInstance<TranscriptItem.Header>().map { it.speaker }
        assertEquals(listOf("Speaker 1", "Speaker 2", "Speaker 1"), headers)
        assertEquals(7, items.size)
    }

    @Test
    fun `lines without a speaker get no header`() {
        val lines = listOf(line("a"), line("b"))
        val items = buildTranscriptItems(lines, lines.indices.toList(), emptySet())
        assertTrue(items.none { it is TranscriptItem.Header })
        assertEquals(2, items.size)
    }

    @Test
    fun `a diarized name wins over the lane label`() {
        assertEquals("Speaker 2", speakerName(line("x", "Speaker 2", SpeechSource.THEM)))
        assertEquals("Them", speakerName(line("x", null, SpeechSource.THEM)))
    }

    @Test
    fun `a gap left by a filter starts a new block even for the same speaker`() {
        val lines = listOf(line("a", "Me"), line("b", "Them"), line("c", "Me"))
        val items = buildTranscriptItems(lines, listOf(0, 2), emptySet())
        assertEquals(2, items.count { it is TranscriptItem.Header })
    }

    @Test
    fun `filters keep the right lines`() {
        val lines = listOf(
            line("a", source = SpeechSource.ME),
            line("b", source = SpeechSource.THEM),
            line("c", source = SpeechSource.ME),
        )
        assertEquals(listOf(0, 2), visibleIndices(lines, TranscriptFilter.ME, emptySet()))
        assertEquals(listOf(1), visibleIndices(lines, TranscriptFilter.THEM, emptySet()))
        assertEquals(listOf(2), visibleIndices(lines, TranscriptFilter.FLAGGED, setOf(2)))
        assertEquals(listOf(0, 1, 2), visibleIndices(lines, TranscriptFilter.ALL, emptySet()))
    }

    @Test
    fun `flagged lines are marked`() {
        val lines = listOf(line("a"), line("b"))
        val items = buildTranscriptItems(lines, listOf(0, 1), setOf(1))
        assertEquals(listOf(false, true), items.filterIsInstance<TranscriptItem.Line>().map { it.flagged })
    }

    @Test
    fun `search ignores case and blank queries`() {
        val lines = listOf(line("We cap Pricing at twelve"), line("nothing here"), line("pricing again"))
        assertEquals(listOf(0, 2), searchHits(lines, "pricing"))
        assertTrue(searchHits(lines, "  ").isEmpty())
    }

    @Test
    fun `match ranges find every occurrence`() {
        val ranges = matchRanges("ab cd ab", "ab")
        assertEquals(listOf(0, 6), ranges.map { it.first })
        assertEquals(2, matchRanges("ab cd AB", "ab").size)
        assertTrue(matchRanges("abc", "").isEmpty())
    }

    @Test
    fun `edited survives a round trip and is absent when false`() {
        val lines = listOf(TranscriptLine("0:01", "fixed", edited = true), TranscriptLine("0:02", "as heard"))
        val back = TranscriptJson.decode(TranscriptJson.encode(lines))
        assertTrue(back[0].edited)
        assertFalse(back[1].edited)
        assertFalse(TranscriptJson.encode(listOf(lines[1])).contains("\"ed\""))
    }
}
