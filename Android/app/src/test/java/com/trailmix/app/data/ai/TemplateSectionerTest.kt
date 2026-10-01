package com.trailmix.app.data.ai

import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SectionSpec
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TemplateSpec
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-19: template enforcement (AI path) and keyword placement (no-AI path). */
class TemplateSectionerTest {

    private val spec = TemplateSpec(
        "ctx",
        listOf(
            SectionSpec("Pain Points", "problems and frustrations"),
            SectionSpec("Product Feedback", "reactions to features"),
            SectionSpec("Follow-ups", "questions to ask next"),
        ),
    )

    private fun b(text: String, source: Provenance = Provenance.TRANSCRIPT) = SummaryBullet(text, source)
    private fun sec(heading: String, vararg bullets: String) = SummarySection(heading, bullets.map { b(it) })
    private fun summary(vararg sections: SummarySection) = StructuredSummary(emptyList(), sections.toList(), emptyList())

    // ── conform ──────────────────────────────────────────────────────────────

    @Test
    fun `conform reorders model sections into template order under template headings`() {
        val out = TemplateSectioner.conform(
            summary(sec("follow ups", "Ask about SSO"), sec("Pain points", "Slow exports")),
            spec,
        )
        assertEquals(listOf("Pain Points", "Follow-ups"), out.sections.map { it.heading })
    }

    @Test
    fun `conform appends model extras after the template sections and keeps their order`() {
        val out = TemplateSectioner.conform(
            summary(sec("Pricing", "Too high"), sec("Pain Points", "Slow"), sec("Roadmap", "Q3")),
            spec,
        )
        assertEquals(listOf("Pain Points", "Pricing", "Roadmap"), out.sections.map { it.heading })
    }

    @Test
    fun `conform merges duplicate matches into one section`() {
        val out = TemplateSectioner.conform(
            summary(sec("Pain Points", "One"), sec("Main pain points", "Two")),
            spec,
        )
        assertEquals(1, out.sections.size)
        assertEquals(listOf("One", "Two"), out.sections.single().bullets.map { it.text })
    }

    @Test
    fun `conform drops empty template sections by default`() {
        val out = TemplateSectioner.conform(summary(sec("Pain Points", "Slow")), spec)
        assertEquals(listOf("Pain Points"), out.sections.map { it.heading })
    }

    @Test
    fun `conform keeps every section as Not discussed when omitEmptySections is false`() {
        val out = TemplateSectioner.conform(summary(sec("Pain Points", "Slow")), spec.copy(omitEmptySections = false))
        assertEquals(listOf("Pain Points", "Product Feedback", "Follow-ups"), out.sections.map { it.heading })
        assertEquals(listOf(TemplateSectioner.NOT_DISCUSSED), out.sections[1].bullets.map { it.text })
    }

    @Test
    fun `conform leaves a template without sections untouched`() {
        val s = summary(sec("Whatever", "x"))
        assertEquals(s, TemplateSectioner.conform(s, SummaryTemplate.NONE.spec))
    }

    @Test
    fun `conform preserves highlights and action items`() {
        val s = StructuredSummary(listOf(b("h")), listOf(sec("Pain Points", "p")), emptyList())
        assertEquals(s.highlights, TemplateSectioner.conform(s, spec).highlights)
    }

    // ── arrange ──────────────────────────────────────────────────────────────

    @Test
    fun `arrange places transcript bullets by keyword overlap and keeps leftovers`() {
        val window = sec(
            "Key topics",
            "The biggest pain is onboarding takes too long",
            "They shared product feedback on the dashboard",
            "We talked about the weather",
        )
        val out = TemplateSectioner.arrange(emptyList(), listOf(window), spec)
        assertEquals(listOf("Pain Points", "Product Feedback", "Key topics"), out.map { it.heading })
        assertEquals("The biggest pain is onboarding takes too long", out[0].bullets.single().text)
        assertEquals("They shared product feedback on the dashboard", out[1].bullets.single().text)
        assertEquals("We talked about the weather", out[2].bullets.single().text)
    }

    @Test
    fun `arrange moves a matching anchor section whole and keeps its heading as a typed bullet`() {
        val anchor = SummarySection(
            "Onboarding problems and frustration",
            listOf(b("Admins say onboarding is painful [evidence]")),
        )
        val out = TemplateSectioner.arrange(listOf(anchor), emptyList(), spec)
        assertEquals(listOf("Pain Points"), out.map { it.heading })
        assertEquals(Provenance.FRAGMENT, out.single().bullets.first().source)
        assertEquals("Onboarding problems and frustration", out.single().bullets.first().text)
        assertEquals(2, out.single().bullets.size)
    }

    @Test
    fun `arrange keeps a non-matching anchor as its own section after the template ones`() {
        val matched = sec("Pain Points", "Slow")
        val stray = sec("Budget approval", "Needs CFO")
        val out = TemplateSectioner.arrange(listOf(stray, matched), emptyList(), spec)
        assertEquals(listOf("Pain Points", "Budget approval"), out.map { it.heading })
        // An anchor already named like the template section adds no duplicate heading bullet.
        assertEquals(listOf("Slow"), out[0].bullets.map { it.text })
    }

    @Test
    fun `arrange shows Not discussed when empty sections are kept`() {
        val out = TemplateSectioner.arrange(
            emptyList(),
            listOf(sec("Key topics", "Their pain is slow exports")),
            spec.copy(omitEmptySections = false),
        )
        assertEquals(listOf("Pain Points", "Product Feedback", "Follow-ups"), out.map { it.heading })
        assertEquals(TemplateSectioner.NOT_DISCUSSED, out[2].bullets.single().text)
    }

    @Test
    fun `arrange without sections returns anchors then others unchanged`() {
        val a = sec("A", "x")
        val o = sec("O", "y")
        assertEquals(listOf(a, o), TemplateSectioner.arrange(listOf(a), listOf(o), SummaryTemplate.NONE.spec))
    }

    // ── DeterministicSummary integration ─────────────────────────────────────

    private val transcript = listOf(
        TranscriptLine("00:10", "Their biggest pain is that exports are slow."),
        TranscriptLine("00:30", "They gave us product feedback about the dashboard."),
        TranscriptLine("00:50", "We also covered the office move."),
    )

    @Test
    fun `deterministic summary places bullets into the template sections`() {
        val out = DeterministicSummary.from("", transcript, spec = spec)!!
        val headings = out.sections.map { it.heading }
        assertEquals("Pain Points", headings.first())
        assertTrue(headings.contains("Product Feedback"))
        assertNull(out.sections.firstOrNull { it.heading == "Follow-ups" })
        // AI-22: the unplaced leftover is no longer "Key topics" but a keyword-named topic.
        val templateHeadings = spec.sections.map { it.heading }
        assertNotNull(out.sections.firstOrNull { it.heading !in templateHeadings && it.heading != "Key topics" })
    }

    @Test
    fun `deterministic summary without a spec or with a context-only spec is unchanged`() {
        val legacy = DeterministicSummary.from("", transcript)
        assertEquals(legacy, DeterministicSummary.from("", transcript, spec = null))
        assertEquals(legacy, DeterministicSummary.from("", transcript, spec = SummaryTemplate.NONE.spec))
    }

    @Test
    fun `deterministic summary with typed anchors still represents every anchor under a template`() {
        val typed = "# Pain points\nPricing concerns"
        val out = DeterministicSummary.from(typed, transcript, spec = spec)!!
        val texts = out.sections.flatMap { listOf(it.heading) + it.bullets.map { b -> b.text } }
        assertTrue(texts.any { it.contains("Pain", ignoreCase = true) })
        assertTrue(texts.any { it.contains("Pricing", ignoreCase = true) })
    }

    @Test
    fun `empty content stays null even when empty sections would be kept`() {
        assertNull(DeterministicSummary.from("", "", spec = spec.copy(omitEmptySections = false)))
    }
}
