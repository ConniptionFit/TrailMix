package com.trailmix.app.data.ai

import com.trailmix.app.data.ai.NoteAnchors.Anchor
import com.trailmix.app.data.ai.NoteAnchors.Kind
import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorCoverageTest {

    private val empty = StructuredSummary(emptyList(), emptyList(), emptyList())

    private fun bullet(text: String) = SummaryBullet(text, Provenance.TRANSCRIPT)

    private val pitchTranscript = listOf(
        TranscriptLine("00:05", "We're at 1.2 million ARR, growing 15% month over month."),
        TranscriptLine("00:20", "Logo churn is about 4% monthly, mostly SMB."),
        TranscriptLine("00:40", "Raising 6 million on a 30 post."),
    )

    @Test
    fun `no anchors leaves the summary untouched`() {
        val s = StructuredSummary(emptyList(), listOf(SummarySection("A", listOf(bullet("x")))), emptyList())
        assertEquals(s, AnchorCoverage.ensure(s, emptyList(), pitchTranscript))
    }

    @Test
    fun `an anchor the model already covered is not duplicated`() {
        val s = StructuredSummary(
            emptyList(),
            listOf(SummarySection("Traction", listOf(bullet("$1.2M ARR growing 15% MoM")))),
            emptyList(),
        )
        val out = AnchorCoverage.ensure(s, listOf(Anchor(Kind.QUESTION, "ARR?")), pitchTranscript)
        assertEquals(s, out)
    }

    @Test
    fun `a missing point is injected as a section with timestamped transcript evidence`() {
        val s = StructuredSummary(emptyList(), listOf(SummarySection("Traction", listOf(bullet("ARR is 1.2M")))), emptyList())
        val out = AnchorCoverage.ensure(s, listOf(Anchor(Kind.POINT, "churn is high")), pitchTranscript)
        val injected = out.sections.single { it.heading == "Churn is high" }
        assertEquals("Logo churn is about 4% monthly, mostly SMB.", injected.bullets.first().text)
        assertEquals("00:20", injected.bullets.first().timestampLabel)
        assertEquals(Provenance.TRANSCRIPT, injected.bullets.first().source)
    }

    @Test
    fun `an unsupported point still gets a section carrying the user's own words`() {
        val out = AnchorCoverage.ensure(empty, listOf(Anchor(Kind.POINT, "book the venue")), pitchTranscript)
        val sec = out.sections.single()
        assertEquals("Book the venue", sec.heading)
        assertEquals(Provenance.FRAGMENT, sec.bullets.single().source)
    }

    @Test
    fun `unanswered question goes to Open Questions, answered one becomes a section`() {
        val anchors = listOf(Anchor(Kind.QUESTION, "ARR?"), Anchor(Kind.QUESTION, "Data room access?"))
        val out = AnchorCoverage.ensure(empty, anchors, pitchTranscript)
        assertEquals(listOf("ARR", AnchorCoverage.OPEN_QUESTIONS), out.sections.map { it.heading })
        assertEquals("Data room access?", out.sections.last().bullets.single().text)
    }

    @Test
    fun `unanswered question joins an existing Open Questions section`() {
        val s = StructuredSummary(
            emptyList(),
            listOf(SummarySection("open questions", listOf(bullet("Cohort churn?")))),
            emptyList(),
        )
        val out = AnchorCoverage.ensure(s, listOf(Anchor(Kind.QUESTION, "Data room access?")), pitchTranscript)
        assertEquals(1, out.sections.size)
        assertEquals(2, out.sections.single().bullets.size)
    }

    @Test
    fun `a transcript question is never used as evidence`() {
        val transcript = listOf(TranscriptLine("00:01", "What is the data room access policy?"))
        val out = AnchorCoverage.ensure(empty, listOf(Anchor(Kind.QUESTION, "Data room access?")), transcript)
        assertEquals(AnchorCoverage.OPEN_QUESTIONS, out.sections.single().heading)
    }

    @Test
    fun `judgment attaches to the matching topic section as a you-noted bullet`() {
        val s = StructuredSummary(
            emptyList(),
            listOf(SummarySection("Retention", listOf(bullet("Logo churn is about 4% monthly")))),
            emptyList(),
        )
        val out = AnchorCoverage.ensure(s, listOf(Anchor(Kind.JUDGMENT, "churn seems high")), pitchTranscript)
        val bullets = out.sections.single().bullets
        assertEquals("You noted: churn seems high", bullets.last().text)
        assertEquals(Provenance.FRAGMENT, bullets.last().source)
    }

    @Test
    fun `judgment with no matching topic lands in Your Take`() {
        val out = AnchorCoverage.ensure(empty, listOf(Anchor(Kind.JUDGMENT, "I don't buy the 2wk estimate")), emptyList())
        assertEquals(AnchorCoverage.YOUR_TAKE, out.sections.single().heading)
    }

    @Test
    fun `injected sections follow anchor order relative to existing ones`() {
        val s = StructuredSummary(
            emptyList(),
            listOf(
                SummarySection("Traction", listOf(bullet("ARR 1.2M"))),
                SummarySection("Round", listOf(bullet("Raising 6 million"))),
            ),
            emptyList(),
        )
        val anchors = listOf(
            Anchor(Kind.POINT, "traction"),
            Anchor(Kind.POINT, "logo churn"),
            Anchor(Kind.POINT, "raising round"),
        )
        val out = AnchorCoverage.ensure(s, anchors, pitchTranscript)
        assertEquals(listOf("Traction", "Logo churn", "Round"), out.sections.map { it.heading })
    }

    @Test
    fun `an action item can cover an anchor`() {
        val s = StructuredSummary(emptyList(), emptyList(), listOf(ActionItem("Send the deck to Bob")))
        val out = AnchorCoverage.ensure(s, listOf(Anchor(Kind.POINT, "send deck Bob")), emptyList())
        assertTrue(out.sections.isEmpty())
    }

    @Test
    fun `every anchor is covered after ensure`() {
        val anchors = NoteAnchors.parse("ARR?\nchurn seems high\nfounder ex-Stripe\nsomething never said\nWhat about hiring?")
        val out = AnchorCoverage.ensure(empty, anchors, pitchTranscript)
        anchors.forEach { assertTrue("uncovered: $it", AnchorCoverage.isCovered(out, it)) }
    }
}
