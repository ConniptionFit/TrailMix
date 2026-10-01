package com.trailmix.app.data.ai

import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredSummaryPromptTest {

    @Test
    fun `capWords leaves short text alone and marks truncation with an ellipsis`() {
        assertEquals("Short bullet here.", StructuredSummaryPrompt.capWords("Short bullet here."))
        val long = (1..30).joinToString(" ") { "w$it" }
        val capped = StructuredSummaryPrompt.capWords(long)
        assertEquals(15, capped.removeSuffix("…").split(" ").size)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun `capWords does not leave dangling punctuation before the ellipsis`() {
        val text = (1..15).joinToString(" ") { "w$it" } + ", extra words after the cut point"
        assertTrue(StructuredSummaryPrompt.capWords(text).endsWith("w15…"))
    }

    @Test
    fun `verbatim anchor match ignores case spacing and end punctuation`() {
        val anchors = NoteAnchors.parse("- Confirm ICP alignment")
        assertTrue(StructuredSummaryPrompt.isVerbatimAnchor("confirm  icp alignment.", anchors))
        assertFalse(StructuredSummaryPrompt.isVerbatimAnchor("Confirm ICP alignment with sales", anchors))
    }

    @Test
    fun `prompt lists numbered anchors with kinds and the data-not-instructions delimiters`() {
        val anchors = NoteAnchors.parse("# plan\nARR?\nchurn seems high")
        val prompt = StructuredSummaryPrompt.build("Be brief.", listOf("Priya"), anchors, "x", "[00:01] hi")
        assertTrue(prompt.contains("1. [HEADING] Plan"))
        assertTrue(prompt.contains("2. [QUESTION] ARR?"))
        assertTrue(prompt.contains("3. [JUDGMENT] churn seems high"))
        assertTrue(prompt.contains("Open Questions"))
        assertTrue(prompt.contains("attribute them to \"you\""))
        assertTrue(prompt.contains("<<<TRANSCRIPT"))
        assertTrue(prompt.contains("Attendees: Priya."))
        assertTrue(prompt.contains("\"details\""))
    }

    @Test
    fun `without anchors the prompt keeps the whole-session rules and typed notes block`() {
        val prompt = StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "[00:01] hi")
        assertTrue(prompt.contains("cover the WHOLE session"))
        assertTrue(prompt.contains("Typed notes:\n(none)"))
        assertFalse(prompt.contains("[HEADING]"))
    }

    @Test
    fun `structured detection needs a newline or a marker`() {
        assertFalse(NoteAnchors.isStructured("Just one pasted paragraph. Two sentences."))
        assertTrue(NoteAnchors.isStructured("one\ntwo"))
        assertTrue(NoteAnchors.isStructured("- only bullet"))
        assertTrue(NoteAnchors.isStructured("# Heading"))
    }

    // ── AI-19 / AI-21 ────────────────────────────────────────────────────────

    private val spec = SummaryTemplate.USER_INTERVIEW.spec

    @Test
    fun `template sections become numbered required headings in order with instructions`() {
        val prompt = StructuredSummaryPrompt.build(spec.meetingContext, emptyList(), emptyList(), "", "[00:01] hi", spec)
        val positions = spec.sections.map { prompt.indexOf(it.heading) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(prompt.contains("1. Participant Background: who they are"))
        assertTrue(prompt.contains("Use EXACTLY these section headings, in this order"))
        assertTrue(prompt.contains("Leave out any section with nothing to report."))
        assertTrue(prompt.contains("Focus on what they said, not what I said"))
        assertFalse(prompt.contains("Next Steps"))
    }

    @Test
    fun `typed notes are placed into matching template sections`() {
        val anchors = NoteAnchors.parse("# plan\nARR?")
        val prompt = StructuredSummaryPrompt.build(spec.meetingContext, emptyList(), anchors, "x", "[00:01] hi", spec)
        assertTrue(prompt.contains("under the listed"))
        assertTrue(prompt.contains("1. [HEADING] Plan"))
        assertTrue(prompt.contains("Open Questions"))
        assertFalse(prompt.contains("one section (or bullet) per"))
    }

    @Test
    fun `not discussed variant is requested when empty sections are kept`() {
        val prompt = StructuredSummaryPrompt.build(
            spec.meetingContext, emptyList(), emptyList(), "", "t", spec.copy(omitEmptySections = false),
        )
        assertTrue(prompt.contains("\"Not discussed\""))
        assertFalse(prompt.contains("Leave out any section"))
    }

    @Test
    fun `context-only spec and null spec give the legacy prompt`() {
        val legacy = StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t")
        assertEquals(legacy, StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t", SummaryTemplate.NONE.spec))
        assertEquals(legacy, StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t", null, null))
        assertFalse(legacy.contains("EXACTLY these section headings"))
    }

    @Test
    fun `profile line leads the prompt and blank is ignored`() {
        val line = UserProfile(name = "Sam", role = "PM").promptLine()
        val prompt = StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t", null, line)
        assertTrue(prompt.startsWith("The note-taker is Sam, PM."))
        val without = StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t", null, "  ")
        assertEquals(StructuredSummaryPrompt.build("G", emptyList(), emptyList(), "", "t"), without)
        assertTrue(without.startsWith("You are structuring"))
    }

    @Test
    fun `largest built-in template prompt overhead stays small`() {
        val largest = SummaryTemplate.entries.maxByOrNull { it.spec.describe().length }!!
        val profile = UserProfile("Sam Taylor", "Head of Product", "Acme Corporation", listOf("roadmap", "pricing", "hiring")).promptLine()
        val anchors = NoteAnchors.parse((1..20).joinToString("\n") { "- typed note number $it about something" })
        val prompt = StructuredSummaryPrompt.build(
            largest.spec.meetingContext, listOf("A", "B"), anchors, "", "", largest.spec, profile,
        )
        // Overhead = everything except the transcript, with the maximum 20 anchors listed: it
        // must leave most of Gemini Nano's ~8k-char context for the transcript.
        assertTrue("prompt overhead ${prompt.length}", prompt.length < 3_600)
    }
}
