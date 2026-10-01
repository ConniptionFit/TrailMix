package com.trailmix.app.data.ai

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SectionSpec
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import com.trailmix.app.data.model.TemplateSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteShapeTest {

    private fun b(text: String) = SummaryBullet(text, Provenance.TRANSCRIPT)
    private fun sec(heading: String, vararg bullets: String) = SummarySection(heading, bullets.map(::b))
    private fun summary(vararg sections: SummarySection, actions: List<ActionItem> = emptyList()) =
        StructuredSummary(emptyList(), sections.toList(), actions)

    // Distinct vocab per section so keyword headings and dedupe behave predictably.
    private val routing = listOf(
        "Routing tables rebuilt overnight for the edge routers.",
        "Routing convergence time dropped after the table compaction.",
        "Router firmware needs the new routing daemon.",
    )
    private val pricing = listOf(
        "Pricing moves to per-seat billing in January.",
        "Enterprise pricing tier adds a volume discount.",
        "Billing system migration blocks the pricing change.",
    )

    @Test
    fun `cleanHeading strips markdown numbering labels punctuation and Title Cases`() {
        assertEquals("Pricing and Billing", NoteShape.cleanHeading("**1. pricing and billing:**"))
        assertEquals("Routing Tables", NoteShape.cleanHeading("Topic: routing tables."))
        assertEquals("Hiring Plan", NoteShape.cleanHeading("## Section 2 - hiring plan"))
        assertEquals("Routing, Table", NoteShape.cleanHeading("15:48 – 23:40 · routing, table"))
        // Acronyms and mixed-case words are left alone; small words stay lowercase mid-heading.
        assertEquals("ARR and iOS Rollout", NoteShape.cleanHeading("ARR and iOS rollout"))
    }

    @Test
    fun `generic and time-range headings are replaced by keyword headings`() {
        val result = NoteShape.apply(
            summary(
                sec("Discussion 1", *routing.toTypedArray()),
                sec("Topic 2", *pricing.toTypedArray()),
                sec("0:00 – 7:00", "Hiring freeze lifts in March for platform roles.", "Hiring managers need approval for platform headcount."),
            ),
        )
        val headings = result.sections.map { it.heading }
        assertTrue(headings.toString(), headings.none { NoteShape.isGenericHeading(it) })
        assertTrue(headings[0], headings[0].contains("Rout"))
        assertTrue(headings[1], headings[1].contains("Pricing") || headings[1].contains("Billing"))
        assertTrue(headings[2], headings[2].contains("Hiring"))
        assertEquals(headings.size, headings.toSet().size)
    }

    @Test
    fun `isGenericHeading catches the stock labels and bare ranges`() {
        listOf("Discussion", "Discussion 1", "Topic 2", "Summary", "Notes", "Key Points", "Other", "General", "7:00 – 14:00", "")
            .forEach { assertTrue("'$it'", NoteShape.isGenericHeading(it)) }
        listOf("Pricing", "Routing and Tables", "Open Questions").forEach { assertFalse(it, NoteShape.isGenericHeading(it)) }
    }

    @Test
    fun `exact and near-duplicate bullets are removed across sections and empties dropped`() {
        val result = NoteShape.apply(
            summary(
                sec("Pricing", "Pricing moves to per-seat billing in January.", "Annual contracts get a ten percent discount."),
                sec("Billing", "Pricing moves to per-seat billing in January!", "Invoices go out on the first of each month."),
                sec("Repeats", "Annual contracts get a ten percent discount."),
                sec("Support", "Support hours extend to weekends.", "Escalations reach the on-call engineer within ten minutes."),
            ),
        )
        assertEquals(listOf("Pricing", "Billing", "Support"), result.sections.map { it.heading })
        assertEquals(2, result.sections[0].bullets.size)
        assertEquals(listOf("Invoices go out on the first of each month."), result.sections[1].bullets.map { it.text })
    }

    @Test
    fun `more than six sections are merged down without fixed sections`() {
        val sections = (1..9).map { i ->
            sec("Topic Number$i", "Alpha$i bravo$i charlie$i delta$i.", *(if (i % 2 == 0) arrayOf("Echo$i foxtrot$i golf$i hotel$i.") else emptyArray()))
        }
        val result = NoteShape.apply(summary(*sections.toTypedArray()))
        assertEquals(NoteShape.MAX_TOPIC_SECTIONS, result.sections.size)
        // Merging moves bullets, never loses them.
        assertEquals(sections.sumOf { it.bullets.size }, result.sections.sumOf { it.bullets.size })
    }

    @Test
    fun `the cap never merges away anchor, Open Questions or template sections`() {
        val anchors = NoteAnchors.parse("# Pricing\n# Hiring")
        val sections = listOf(
            sec("Pricing", "Pricing moves to per-seat billing in January."),
            sec("Hiring", "Hiring freeze lifts in March."),
            sec("Alpha Topic", "Alpha one two three."),
            sec("Bravo Topic", "Bravo four five six."),
            sec("Charlie Topic", "Charlie seven eight nine."),
            sec("Delta Topic", "Delta ten eleven twelve."),
            sec("Echo Topic", "Echo thirteen fourteen fifteen."),
            sec(AnchorCoverage.OPEN_QUESTIONS, "What about the budget?"),
        )
        val headings = NoteShape.apply(summary(*sections.toTypedArray()), anchors).sections.map { it.heading }
        assertEquals(NoteShape.MAX_TOPIC_SECTIONS, headings.size)
        assertTrue(headings.containsAll(listOf("Pricing", "Hiring", AnchorCoverage.OPEN_QUESTIONS)))
    }

    @Test
    fun `template sections are neither capped nor merged and keep their headings`() {
        val spec = TemplateSpec("ctx", (1..8).map { SectionSpec("Area $it") })
        val sections = (1..8).map { sec("Area $it", "Bullet about area$it with detail$it.") }
        val result = NoteShape.apply(summary(*sections.toTypedArray()), emptyList(), spec)
        assertEquals(8, result.sections.size)
        assertEquals((1..8).map { "Area $it" }, result.sections.map { it.heading })
    }

    @Test
    fun `a very short note collapses to a single section`() {
        val result = NoteShape.apply(
            summary(
                sec("Discussion 1", "Pricing moves to per-seat billing in January."),
                sec("Discussion 2", "Invoices go out monthly."),
            ),
        )
        assertEquals(1, result.sections.size)
        assertEquals(2, result.sections.single().bullets.size)
        assertFalse(NoteShape.isGenericHeading(result.sections.single().heading))
    }

    @Test
    fun `short notes with a locked section are not collapsed`() {
        val anchors = NoteAnchors.parse("# Pricing")
        val result = NoteShape.apply(summary(sec("Pricing", "Per-seat billing."), sec("Other Stuff", "Invoices monthly.")), anchors)
        assertEquals(2, result.sections.size)
    }

    @Test
    fun `highlights and action items pass through untouched`() {
        val actions = listOf(ActionItem("Send the deck", owner = "Sam"))
        val input = StructuredSummary(listOf(b("Old note highlight")), listOf(sec("Pricing", "a b c d.", "e f g h.", "i j k l.", "m n o p.")), actions)
        val out = NoteShape.apply(input)
        assertEquals(input.highlights, out.highlights)
        assertEquals(actions, out.actionItems)
    }

    @Test
    fun `a model-made Next Steps section is dropped when real action items exist`() {
        val actions = listOf(ActionItem("Send the deck", owner = "Sam"))
        val shaped = NoteShape.apply(
            summary(sec("Routing", *routing.toTypedArray()), sec("Next Steps", "Sam to send the deck."), actions = actions),
        )

        assertEquals(listOf("Routing"), shaped.sections.map { it.heading })
        assertEquals(actions, shaped.actionItems)
    }

    @Test
    fun `a Next Steps section is kept when there are no action items to stand in for it`() {
        val shaped = NoteShape.apply(summary(sec("Routing", *routing.toTypedArray()), sec("Next Steps", "Sam to send the deck.")))

        assertTrue(shaped.sections.any { it.heading.equals("Next Steps", true) })
    }
}
