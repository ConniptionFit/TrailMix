package com.trailmix.app.data.ai

import com.trailmix.app.data.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicSummaryTest {

    @Test
    fun `tiny notes stay flat (null) rather than over-structured`() {
        assertNull(DeterministicSummary.from("", ""))
        assertNull(DeterministicSummary.from("Just one quick thought.", ""))
        assertNull(DeterministicSummary.from("Two sentences. Only two here.", ""))
    }

    @Test
    fun `transcript-only content becomes a Key topics section`() {
        val transcript = "We reviewed the roadmap. Design is on track. QA starts Monday."
        val summary = DeterministicSummary.from("", transcript)!!
        assertTrue(summary.highlights.isEmpty())
        assertEquals(listOf("Key topics"), summary.sections.map { it.heading })
        assertEquals(3, summary.sections.single().bullets.size)
        assertTrue(summary.sections.single().bullets.all { it.source == Provenance.TRANSCRIPT })
    }

    @Test
    fun `typed notes and transcript split into two provenance sections`() {
        val typed = "Client wants a demo. Budget is approved."
        val transcript = "The team discussed timelines. Launch is targeted for fall."
        val summary = DeterministicSummary.from(typed, transcript)!!
        assertEquals(listOf("Your notes", "Key topics"), summary.sections.map { it.heading })
        assertTrue(summary.sections[0].bullets.all { it.source == Provenance.FRAGMENT })
        assertTrue(summary.sections[1].bullets.all { it.source == Provenance.TRANSCRIPT })
    }

    @Test
    fun `action-cue sentences are lifted into Action Items, out of the sections`() {
        val transcript =
            "We agreed on the scope. I'll send the contract by Friday. " +
                "Charlie needs to schedule the kickoff. The design looks solid."
        val summary = DeterministicSummary.from("", transcript)!!
        val actionTexts = summary.actionItems.map { it.text }
        assertTrue(actionTexts.any { it.contains("send the contract") })
        assertTrue(actionTexts.any { it.contains("schedule the kickoff") })
        // The two non-action sentences remain as Key topics bullets.
        val topicTexts = summary.sections.single().bullets.map { it.text }
        assertTrue(topicTexts.any { it.contains("agreed on the scope") })
        assertTrue(topicTexts.none { it.contains("send the contract") })
    }

    @Test
    fun `duplicate action sentences are de-duplicated`() {
        val typed = "I'll email the notes. I'll email the notes."
        val transcript = "Meeting wrapped up. Everyone agreed on next steps overall."
        val summary = DeterministicSummary.from(typed, transcript)!!
        assertEquals(1, summary.actionItems.count { it.text.contains("email the notes") })
    }
}
