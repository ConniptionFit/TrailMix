package com.trailmix.app.data.ai

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
}
