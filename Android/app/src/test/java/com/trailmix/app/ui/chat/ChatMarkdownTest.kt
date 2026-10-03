package com.trailmix.app.ui.chat

import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.SummarySection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun parsesHeadingsBulletsNumbersAndParagraphs() {
        val blocks = ChatMarkdown.parse("## Decisions\n- Ship Friday\n* Hold pricing\n1. Email Sam\n2) Book room\n\nOne line\nsecond line")
        assertEquals(
            listOf(
                ChatBlock.Heading("Decisions"),
                ChatBlock.Bullet("Ship Friday"),
                ChatBlock.Bullet("Hold pricing"),
                ChatBlock.Numbered(1, "Email Sam"),
                ChatBlock.Numbered(2, "Book room"),
                ChatBlock.Paragraph("One line second line"),
            ),
            blocks,
        )
    }

    @Test
    fun unknownShapesFallThroughAsParagraphs() {
        assertEquals(listOf(ChatBlock.Paragraph("Just text.")), ChatMarkdown.parse("Just text."))
        assertTrue(ChatMarkdown.parse("   \n\n").isEmpty())
    }

    @Test
    fun boldSpansSplitOnDoubleAsterisks() {
        assertEquals(
            listOf(ChatSpan("Ship ", false), ChatSpan("Friday", true), ChatSpan(" please", false)),
            ChatMarkdown.spans("Ship **Friday** please"),
        )
    }

    @Test
    fun unmatchedMarkerIsNotLeftInText() {
        assertEquals(listOf(ChatSpan("Ship Friday", false)), ChatMarkdown.spans("Ship **Friday"))
    }

    @Test
    fun plainDropsMarkers() {
        assertEquals("Decisions\n• Ship Friday\n1. Email Sam", ChatMarkdown.plain("# Decisions\n- Ship **Friday**\n1. Email Sam"))
    }

    @Test
    fun addToNoteAppendsATypedSection() {
        val base = StructuredSummary(
            highlights = emptyList(),
            sections = listOf(SummarySection("Pricing", listOf(SummaryBullet("Hold", Provenance.TRANSCRIPT)))),
            actionItems = emptyList(),
        )
        val out = ChatToNote.append(base, "## Answer\n- Ship **Friday**\n- Email Sam")
        assertEquals(2, out.sections.size)
        val added = out.sections.last()
        assertEquals("From chat", added.heading)
        assertEquals(listOf("Ship Friday", "Email Sam"), added.bullets.map { it.text })
        assertTrue(added.bullets.all { it.source == Provenance.FRAGMENT })
        assertEquals(base.sections.first(), out.sections.first())
    }

    @Test
    fun addToNoteWithNothingToAddChangesNothing() {
        val base = StructuredSummary(emptyList(), emptyList(), emptyList())
        assertEquals(base, ChatToNote.append(base, "## Only a heading"))
    }
}
