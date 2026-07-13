package com.trailmix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `segments round-trip through JSON`() {
        val segments = listOf(
            NoteSegment("Client wants launch by Sept 15.", Provenance.FRAGMENT),
            NoteSegment("Budget is approved through Q3.", Provenance.TRANSCRIPT),
            NoteSegment("Text with \"quotes\" and — dashes.", Provenance.FRAGMENT),
        )
        assertEquals(segments, SegmentsJson.decode(SegmentsJson.encode(segments)))
    }

    @Test
    fun `transcript lines round-trip through JSON`() {
        val lines = listOf(
            TranscriptLine("0:12", "Budget's approved through Q3 on our end."),
            TranscriptLine("1:03", "I'll get those to you by Friday."),
        )
        assertEquals(lines, TranscriptJson.decode(TranscriptJson.encode(lines)))
    }

    @Test
    fun `decode of malformed JSON returns empty rather than crashing`() {
        assertTrue(SegmentsJson.decode("not json").isEmpty())
        assertTrue(TranscriptJson.decode("{\"broken\":").isEmpty())
    }

    @Test
    fun `unknown provenance value falls back to transcript`() {
        val json = """[{"t":"hello","s":"SOMETHING_NEW"}]"""
        assertEquals(Provenance.TRANSCRIPT, SegmentsJson.decode(json).single().source)
    }
}
