package com.trailmix.app.data.ai

import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SummaryStyle
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── AI-05: long sessions, timestamps, and presentation style ─────────────

    // Banks of different lengths so consecutive lines don't rhyme with each other — near-
    // identical lines are correctly collapsed by the dedupe pass, which would make a
    // "coverage" assertion pass for the wrong reason.
    private val topics = listOf(
        "Sharding", "Caching", "Replication", "Indexing", "Compression", "Batching",
        "Streaming", "Partitioning", "Vectorization", "Prefetching", "Quantization",
    )
    private val metrics = listOf("tail latency", "write amplification", "cold-start time", "memory ceiling", "queue depth", "error budget", "index size")
    private val places = listOf("the checkout service", "our search cluster", "the billing pipeline", "the mobile edge", "the analytics warehouse")

    /** A talk of [minutes] minutes, one genuinely distinct line per minute. */
    private fun talk(minutes: Int) = (0 until minutes).map { i ->
        TranscriptLine(
            "$i:00",
            "${topics[i % topics.size]} cut ${metrics[i % metrics.size]} by ${i + 7} percent " +
                "across ${places[i % places.size]}.",
        )
    }

    @Test
    fun `a 45-minute talk is summarized end to end, not just its opening`() {
        val summary = DeterministicSummary.from("", talk(45), SummaryStyle.PRESENTATION)!!

        // Multiple time-ranged sections rather than one front-loaded "Key topics" block.
        assertTrue("expected windowed sections", summary.sections.size > 1)
        assertTrue(summary.sections.none { it.heading == "Key topics" })
        assertTrue(summary.sections.first().heading.contains("–"))

        // The regression this whole feature exists to prevent: v1.9.0 took the first 25
        // sentences (~3 minutes) and silently dropped the rest of the talk.
        val latest = summary.sections
            .flatMap { it.bullets }
            .mapNotNull { it.timestampLabel?.substringBefore(':')?.toIntOrNull() }
            .max()
        assertTrue("summary stopped at minute $latest of a 45-minute talk", latest > 30)
    }

    @Test
    fun `transcript bullets carry their capture offset and typed bullets do not`() {
        val summary = DeterministicSummary.from(
            "My own thought about this topic.",
            talk(20),
            SummaryStyle.PRESENTATION,
        )!!
        val yourNotes = summary.sections.first { it.heading == "Your notes" }
        assertTrue(yourNotes.bullets.all { it.timestampLabel == null })

        val spoken = summary.sections.filter { it.heading != "Your notes" }.flatMap { it.bullets }
        assertTrue(spoken.isNotEmpty())
        assertTrue(spoken.all { it.source == Provenance.TRANSCRIPT })
        assertTrue(spoken.all { it.timestampLabel != null })
    }

    @Test
    fun `a presenter's instructional voice is content, not action items`() {
        val transcript = listOf(
            TranscriptLine("1:00", "You should index that column before it grows."),
            TranscriptLine("2:00", "Let's look at the next benchmark together."),
            TranscriptLine("3:00", "We'll need to consider replication lag carefully."),
            TranscriptLine("4:00", "Make sure the cache warms before peak traffic arrives."),
        )
        val summary = DeterministicSummary.from("", transcript, SummaryStyle.PRESENTATION)!!

        assertTrue(
            "the speaker teaching is not the listener's to-do list",
            summary.actionItems.isEmpty(),
        )
        // And because cue matches are lifted OUT of sections, suppressing them is what keeps
        // the topic bullets from being gutted.
        val bullets = summary.sections.flatMap { it.bullets }.map { it.text }
        assertTrue(bullets.any { it.contains("index that column") })
        assertTrue(bullets.any { it.contains("replication lag") })
    }

    @Test
    fun `the same talk in discussion style does treat those cues as actions`() {
        val transcript = listOf(
            TranscriptLine("1:00", "You should index that column before it grows."),
            TranscriptLine("2:00", "Let's look at the next benchmark together."),
            TranscriptLine("3:00", "The design review went well overall."),
        )
        val summary = DeterministicSummary.from("", transcript, SummaryStyle.DISCUSSION)!!
        assertTrue(summary.actionItems.isNotEmpty())
    }

    @Test
    fun `during a talk the listener's own typed commitments still become action items`() {
        val typed = "I'll try this indexing approach on our staging cluster."
        val summary = DeterministicSummary.from(typed, talk(12), SummaryStyle.PRESENTATION)!!

        assertEquals(1, summary.actionItems.size)
        assertTrue(summary.actionItems.single().text.contains("staging cluster"))
        assertEquals(Provenance.FRAGMENT, summary.actionItems.single().source)
        // Lifted out of the notes section rather than duplicated into both.
        val yourNotes = summary.sections.firstOrNull { it.heading == "Your notes" }
        assertFalse(yourNotes?.bullets.orEmpty().any { it.text.contains("staging cluster") })
    }

    @Test
    fun `an explicitly announced action item is still caught during a talk`() {
        val transcript = listOf(
            TranscriptLine("1:00", "Your homework is to benchmark this on your own dataset."),
            TranscriptLine("2:00", "The compiler rewrites those loops automatically for you."),
            TranscriptLine("3:00", "Memory bandwidth is the real constraint in most cases."),
        )
        val summary = DeterministicSummary.from("", transcript, SummaryStyle.PRESENTATION)!!
        assertEquals(1, summary.actionItems.size)
        assertTrue(summary.actionItems.single().text.contains("benchmark"))
        assertEquals("1:00", summary.actionItems.single().timestampLabel)
    }

    @Test
    fun `presentation style drops ASR filler and backchannel from bullets`() {
        val transcript = listOf(
            TranscriptLine("0:10", "Right."),
            TranscriptLine("0:20", "Next slide."),
            TranscriptLine("0:30", "So, um, the latency, you know, dropped by half after sharding."),
            TranscriptLine("0:40", "Throughput doubled once we removed the global lock."),
            TranscriptLine("0:50", "Observability tooling caught that regression early."),
        )
        val summary = DeterministicSummary.from("", transcript, SummaryStyle.PRESENTATION)!!
        val bullets = summary.sections.flatMap { it.bullets }.map { it.text }

        assertFalse(bullets.any { it == "Right." || it == "Next slide." })
        assertTrue(bullets.any { it == "The latency dropped by half after sharding." })
    }
}
