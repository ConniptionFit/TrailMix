package com.trailmix.app.data.ai

import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-28: splitting a long session into reply-sized parts and merging the structured results. */
class StructuredPartsTest {

    private fun b(text: String) = SummaryBullet(text, Provenance.TRANSCRIPT)

    @Test
    fun `text that fits is one part and long text splits on line boundaries`() {
        assertEquals(listOf("a\nb"), StructuredParts.split("a\nb", maxChars = 10))

        val lines = (1..9).map { "line$it" } // 5 chars + newline each
        val parts = StructuredParts.split(lines.joinToString("\n"), maxChars = 20)

        assertTrue(parts.all { it.length <= 20 })
        assertEquals(lines, parts.flatMap { it.lines() }) // nothing lost, order kept
        assertTrue(parts.size > 1)
    }

    @Test
    fun `a single line longer than the limit stays whole and blank text is one empty part`() {
        val long = "x".repeat(50)

        assertEquals(listOf(long), StructuredParts.split(long, maxChars = 10))
        assertEquals(listOf("[0:10] a", "[0:20] " + long), StructuredParts.split("[0:10] a\n[0:20] $long", maxChars = 12))
        assertEquals(listOf(""), StructuredParts.split(""))
        assertEquals(listOf("  "), StructuredParts.split("  "))
    }

    @Test
    fun `a 13 minute sized transcript becomes several parts that each fit the reply budget`() {
        val lines = (0 until 60).map { "[${it / 6}:${(it % 6) * 10}] ${"word ".repeat(18).trim()}" } // ~100 chars each
        val parts = StructuredParts.split(lines.joinToString("\n"))

        assertTrue("expected several parts, got ${parts.size}", parts.size >= 5)
        assertTrue(parts.all { it.length <= StructuredParts.PART_CHARS })
        assertEquals(lines, parts.flatMap { it.lines() })
    }

    @Test
    fun `sections with the same heading merge in order of first appearance`() {
        val merged = StructuredParts.mergeSections(
            listOf(
                SummarySection("Pricing", listOf(b("one"))),
                SummarySection("Hiring", listOf(b("two"))),
                SummarySection("pricing.", listOf(b("three"), b("four"))),
                SummarySection("PRICING", listOf(b("five"))),
            ),
        )

        assertEquals(listOf("Pricing", "Hiring"), merged.map { it.heading })
        assertEquals(listOf("one", "three", "four", "five"), merged[0].bullets.map { it.text })
        assertEquals(listOf("two"), merged[1].bullets.map { it.text })
    }

    @Test
    fun `action items repeated across parts are kept once with the first occurrence`() {
        val first = ActionItem("Send the deck", owner = "Sam", deadline = "Friday")
        val merged = StructuredParts.mergeActions(
            listOf(first, ActionItem("send the deck!"), ActionItem("Book the room"), ActionItem("Send  the deck")),
        )

        assertEquals(listOf(first, ActionItem("Book the room")), merged)
        assertTrue(StructuredParts.mergeActions(emptyList()).isEmpty())
    }
}
