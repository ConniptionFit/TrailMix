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
    fun `transcript lines with a speaker label round-trip through JSON`() {
        val lines = listOf(TranscriptLine("0:12", "Let's ship it.", speakerLabel = "Speaker 1"))
        assertEquals(lines, TranscriptJson.decode(TranscriptJson.encode(lines)))
    }

    @Test
    fun `transcript JSON saved before AI-01 decodes with a null speaker label`() {
        val preAi01Json = """[{"l":"0:12","t":"Budget's approved."}]"""
        assertEquals(
            listOf(TranscriptLine("0:12", "Budget's approved.", speakerLabel = null)),
            TranscriptJson.decode(preAi01Json),
        )
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

    // ── Custom summary templates (AI-03, v1.8.0) ────────────────────────────

    @Test
    fun `custom templates JSON round-trips and skips malformed entries`() {
        val templates = listOf(
            CustomSummaryTemplate("Sales call", "Prefer sections like Needs, Objections, Next Steps."),
            CustomSummaryTemplate("Retro", "Prefer sections like Went Well, Didn't, Actions."),
        )
        assertEquals(templates, CustomTemplatesJson.decode(CustomTemplatesJson.encode(templates)))
        assertEquals(emptyList<CustomSummaryTemplate>(), CustomTemplatesJson.decode(null))
        assertEquals(emptyList<CustomSummaryTemplate>(), CustomTemplatesJson.decode("not json"))
        // An entry missing its guidance is skipped, not crashed on.
        assertEquals(
            listOf(CustomSummaryTemplate("Kept", "Has guidance.")),
            CustomTemplatesJson.decode("""[{"n":"Nameless"},{"n":"Kept","g":"Has guidance."}]"""),
        )
    }

    @Test
    fun `template options list built-ins first then customs with prefixed stored values`() {
        val customs = listOf(CustomSummaryTemplate("Sales call", "Custom guidance."))
        val options = TemplateOptions.all(customs)
        assertEquals(SummaryTemplate.entries.size + 1, options.size)
        assertEquals(SummaryTemplate.NONE.name, options.first().stored)
        val custom = options.last()
        assertEquals("custom:Sales call", custom.stored)
        assertEquals("Sales call", custom.label)
        assertTrue(custom.isCustom)
    }

    @Test
    fun `guidance resolves for built-ins and customs and falls back to NONE when deleted`() {
        val customs = listOf(CustomSummaryTemplate("Sales call", "Custom guidance."))
        assertEquals(SummaryTemplate.ONE_ON_ONE.guidance, TemplateOptions.guidanceFor("ONE_ON_ONE", customs))
        assertEquals("Custom guidance.", TemplateOptions.guidanceFor("custom:Sales call", customs))
        // A stored custom whose template was since deleted degrades to the flat default.
        assertEquals(SummaryTemplate.NONE.guidance, TemplateOptions.guidanceFor("custom:Gone", customs))
        assertEquals(SummaryTemplate.NONE.guidance, TemplateOptions.guidanceFor(null, emptyList()))
        assertEquals(SummaryTemplate.NONE.guidance, TemplateOptions.guidanceFor("SALES_PITCH", emptyList()))
    }
}
