package com.trailmix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** UX-04 (v1.6.0): section reordering on the structured summary. */
class StructuredSummaryMoveTest {

    private fun summary(vararg headings: String) = StructuredSummary(
        highlights = listOf(SummaryBullet("h", Provenance.FRAGMENT)),
        sections = headings.map { SummarySection(it, emptyList()) },
        actionItems = emptyList(),
    )

    @Test
    fun `moves a section down`() {
        val moved = summary("A", "B", "C").moveSection(0, 1)
        assertEquals(listOf("B", "A", "C"), moved.sections.map { it.heading })
    }

    @Test
    fun `moves a section up`() {
        val moved = summary("A", "B", "C").moveSection(2, 0)
        assertEquals(listOf("C", "A", "B"), moved.sections.map { it.heading })
    }

    @Test
    fun `same index is a no-op returning the same instance`() {
        val s = summary("A", "B")
        assertSame(s, s.moveSection(1, 1))
    }

    @Test
    fun `out of range indices are a no-op`() {
        val s = summary("A", "B")
        assertSame(s, s.moveSection(0, 5))
        assertSame(s, s.moveSection(-1, 0))
    }

    @Test
    fun `highlights and action items are untouched`() {
        val s = summary("A", "B").moveSection(0, 1)
        assertEquals(1, s.highlights.size)
        assertEquals(0, s.actionItems.size)
    }

    @Test
    fun `reorder survives a JSON round-trip`() {
        val moved = summary("A", "B", "C").moveSection(0, 2)
        val decoded = StructuredSummaryJson.decode(StructuredSummaryJson.encode(moved))!!
        assertEquals(listOf("B", "C", "A"), decoded.sections.map { it.heading })
    }
}
