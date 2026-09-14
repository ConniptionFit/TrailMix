package com.trailmix.app.data.ai

import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossNoteRetrievalTest {

    private fun note(id: Long, title: String, vararg lines: Pair<String, String>) = NoteEntity(
        id = id,
        title = title,
        segmentsJson = "[]",
        transcriptJson = TranscriptJson.encode(lines.map { TranscriptLine(it.first, it.second) }),
        typedFragments = "",
        durationMs = 60_000,
        createdAtEpochMs = 1_785_000_000_000,
    )

    private val noteA = note(
        id = 1,
        title = "Kickoff",
        "0:01" to "Budget only mentioned here.",
        "0:50" to "Budget fully approved later.",
        "1:10" to "Nothing relevant said.",
    )
    private val noteB = note(
        id = 2,
        title = "Followup",
        "0:10" to "Finance approved the budget quickly.",
        "0:20" to "Timeline questions remained open.",
    )

    @Test
    fun `a blank question ranks nothing`() {
        assertEquals(emptyList<Any>(), CrossNoteRetrieval.rank("   ", listOf(noteA, noteB)))
    }

    @Test
    fun `non-matching lines are excluded`() {
        val ranked = CrossNoteRetrieval.rank("budget approved", listOf(noteA, noteB))
        assertTrue(ranked.none { it.line.text == "Nothing relevant said." })
        assertTrue(ranked.none { it.line.text == "Timeline questions remained open." })
    }

    @Test
    fun `ranking crosses note boundaries by relevance, not by note order`() {
        // Both words match noteB's only relevant line and noteA's second line (score 1.0);
        // noteA's first line matches only one of the two words (score 0.5) — despite noteA
        // being passed first, its weaker line ranks behind noteB's stronger one.
        val ranked = CrossNoteRetrieval.rank("budget approved", listOf(noteA, noteB))
        val order = ranked.map { it.note.title to it.line.label }
        assertEquals(listOf("Kickoff" to "0:50", "Followup" to "0:10", "Kickoff" to "0:01"), order)
    }

    @Test
    fun `buildContext groups by note and restores chronological order within each note`() {
        val result = CrossNoteRetrieval.buildContext("budget approved", listOf(noteA, noteB), maxChars = 1_000)
        assertEquals(
            "### Kickoff\n" +
                "[0:01] Budget only mentioned here.\n" +
                "[0:50] Budget fully approved later.\n\n" +
                "### Followup\n" +
                "[0:10] Finance approved the budget quickly.",
            result,
        )
    }

    @Test
    fun `buildContext stops admitting once the next candidate would exceed the budget`() {
        // Mirrors TranscriptCoverage.evenSampleLines' own greedy-then-stop convention: once
        // something doesn't fit, later (smaller) candidates aren't tried either.
        val result = CrossNoteRetrieval.buildContext("budget approved", listOf(noteA, noteB), maxChars = 90)
        assertEquals("### Kickoff\n[0:50] Budget fully approved later.", result)
    }

    @Test
    fun `buildContext always admits at least one line even over a tiny budget`() {
        val solo = note(id = 3, title = "Solo", "0:01" to "Budget approved immediately.")
        val result = CrossNoteRetrieval.buildContext("budget approved", listOf(solo), maxChars = 1)
        assertEquals("### Solo\n[0:01] Budget approved immediately.", result)
    }

    @Test
    fun `buildContext is empty when nothing matches`() {
        assertEquals("", CrossNoteRetrieval.buildContext("xyzzy plugh", listOf(noteA, noteB), maxChars = 1_000))
    }
}
