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

    @Test
    fun `string list round-trips through JSON`() {
        val names = listOf("Charlie", "JP", "Priya")
        assertEquals(names, StringListJson.decode(StringListJson.encode(names)))
    }

    @Test
    fun `string list decode of null or malformed JSON returns empty`() {
        assertTrue(StringListJson.decode(null).isEmpty())
        assertTrue(StringListJson.decode("").isEmpty())
        assertTrue(StringListJson.decode("not json").isEmpty())
    }

    @Test
    fun `structured summary round-trips through JSON`() {
        val summary = StructuredSummary(
            highlights = listOf(SummaryBullet("Decided to ship Friday.", Provenance.TRANSCRIPT, "we'll ship Friday")),
            sections = listOf(
                SummarySection(
                    heading = "Blockers",
                    bullets = listOf(SummaryBullet("Waiting on design review.", Provenance.FRAGMENT, null)),
                ),
            ),
            actionItems = listOf(
                ActionItem("File the ticket", owner = "Charlie", deadline = "Friday", source = Provenance.TRANSCRIPT),
            ),
        )
        val decoded = StructuredSummaryJson.decode(StructuredSummaryJson.encode(summary))
        assertEquals(summary, decoded)
    }

    @Test
    fun `structured summary decode of null, blank, or empty-content JSON returns null`() {
        assertEquals(null, StructuredSummaryJson.decode(null))
        assertEquals(null, StructuredSummaryJson.decode(""))
        assertEquals(null, StructuredSummaryJson.decode("""{"highlights":[],"sections":[],"actionItems":[]}"""))
        assertEquals(null, StructuredSummaryJson.decode("not json"))
    }

    @Test
    fun `summary template falls back to NONE for unknown or null stored value`() {
        assertEquals(SummaryTemplate.NONE, SummaryTemplate.fromStored(null))
        assertEquals(SummaryTemplate.NONE, SummaryTemplate.fromStored("SOMETHING_NEW"))
        assertEquals(SummaryTemplate.ONE_ON_ONE, SummaryTemplate.fromStored("ONE_ON_ONE"))
    }

    @Test
    fun `retired Sales Pitch template loads as NONE and Learning replaces it`(): Unit {
        // UX-05 (v1.7.0): SALES_PITCH was removed — old notes that stored it must still
        // load without crashing, falling back to NONE.
        assertEquals(SummaryTemplate.NONE, SummaryTemplate.fromStored("SALES_PITCH"))
        assertEquals(SummaryTemplate.LEARNING, SummaryTemplate.fromStored("LEARNING"))
        assertTrue(SummaryTemplate.entries.none { it.name == "SALES_PITCH" })
    }
}
