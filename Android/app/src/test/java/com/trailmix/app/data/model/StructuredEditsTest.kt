package com.trailmix.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredEditsTest {
    private fun bullet(text: String, source: Provenance = Provenance.TRANSCRIPT) =
        SummaryBullet(text = text, source = source, sourceExcerpt = "quote", timestampLabel = "11:56", details = listOf("sub"))

    private val base = StructuredSummary(
        highlights = listOf(bullet("Legacy highlight")),
        sections = listOf(
            SummarySection("Pricing", listOf(bullet("Board wants it live"), bullet("12 months is short", Provenance.FRAGMENT))),
            SummarySection("Onboarding", listOf(bullet("Default template"))),
        ),
        actionItems = listOf(ActionItem(text = "Rerun the cohort model", owner = "Maya", deadline = "Fri")),
    )

    @Test fun highlightsBecomeTheFirstSection() {
        val s = StructuredEdits.asSections(base)
        assertTrue(s.highlights.isEmpty())
        assertEquals(listOf("Highlights", "Pricing", "Onboarding"), s.sections.map { it.heading })
        assertEquals("Legacy highlight", s.sections[0].bullets[0].text)
        assertSame(s, StructuredEdits.asSections(s))
    }

    @Test fun editingTextMarksEditedAndKeepsProvenance() {
        val s = StructuredEdits.editBullet(base, 0, 0, "  Board wants it live before Q1 ")
        val b = s.sections[0].bullets[0]
        assertEquals("Board wants it live before Q1", b.text)
        assertTrue(b.edited)
        assertEquals(Provenance.TRANSCRIPT, b.source)
        assertEquals("11:56", b.timestampLabel)
        assertTrue(b.details.isEmpty())
    }

    @Test fun unchangedTextIsNotAnEdit() {
        val s = StructuredEdits.editBullet(base, 0, 0, " Board wants it live ")
        assertFalse(s.sections[0].bullets[0].edited)
        assertEquals(0, StructuredEdits.editCount(s))
    }

    @Test fun outOfRangeIsANoOp() {
        assertSame(base, StructuredEdits.editBullet(base, 9, 0, "x"))
        assertEquals(base, StructuredEdits.editBullet(base, 0, 9, "x"))
        assertEquals(base, StructuredEdits.removeBullet(base, 0, 9))
        assertEquals(base, StructuredEdits.toggleDone(base, 4))
        assertEquals(base, StructuredEdits.removeAction(base, 4))
    }

    @Test fun editCountCountsHeadingsPointsAndSteps() {
        var s = StructuredEdits.renameSection(base, 1, "Onboarding flow")
        s = StructuredEdits.editBullet(s, 0, 0, "Changed")
        s = StructuredEdits.editAction(s, 0, "Rerun the model", "Maya", "Fri")
        assertEquals(3, StructuredEdits.editCount(s))
    }

    @Test fun addedPointIsTypedAndNotCountedAsEdit() {
        val s = StructuredEdits.addBullet(base, 1, "Check empty state")
        val added = s.sections[1].bullets.last()
        assertEquals(Provenance.FRAGMENT, added.source)
        assertFalse(added.edited)
        assertEquals(2, s.sections[1].bullets.size)
    }

    @Test fun checkingAStepKeepsItInPlace() {
        val s = StructuredEdits.toggleDone(base, 0)
        assertTrue(s.actionItems[0].done)
        assertFalse(s.actionItems[0].edited)
        assertFalse(StructuredEdits.toggleDone(s, 0).actionItems[0].done)
    }

    @Test fun clearingOwnerAndDeadlineStoresNull() {
        val s = StructuredEdits.editAction(base, 0, "Rerun the cohort model", " ", "")
        assertNull(s.actionItems[0].owner)
        assertNull(s.actionItems[0].deadline)
        assertTrue(s.actionItems[0].edited)
    }

    @Test fun movingBulletsReorders() {
        val s = StructuredEdits.moveBullet(base, 0, 0, 1)
        assertEquals("12 months is short", s.sections[0].bullets[0].text)
        assertEquals(base, StructuredEdits.moveBullet(base, 0, 1, 1))
    }

    @Test fun cleanedDropsBlankPointsStepsAndEmptySections() {
        var s = StructuredEdits.addBullet(base, 0)
        s = StructuredEdits.addAction(s)
        s = StructuredEdits.addSection(s)
        val c = StructuredEdits.cleaned(s)
        assertEquals(2, c.sections.size)
        assertEquals(2, c.sections[0].bullets.size)
        assertEquals(1, c.actionItems.size)
    }

    @Test fun cleanedKeepsAHeadedSectionWithNoPoints() {
        val s = StructuredEdits.addSection(base, "Risks")
        assertNotNull(StructuredEdits.cleaned(s).sections.firstOrNull { it.heading == "Risks" })
    }

    @Test fun jsonRoundTripKeepsDoneAndEditedFlags() {
        var s = StructuredEdits.editBullet(base, 0, 0, "Changed")
        s = StructuredEdits.renameSection(s, 1, "Renamed")
        s = StructuredEdits.toggleDone(s, 0)
        s = StructuredEdits.editAction(s, 0, "Rerun", "Maya", "Fri")
        val back = StructuredSummaryJson.decode(StructuredSummaryJson.encode(s))!!
        assertEquals(s, back)
    }

    @Test fun oldJsonWithoutNewKeysDecodesFalse() {
        val old = """{"highlights":[],"sections":[{"heading":"A","bullets":[{"t":"x","s":"TRANSCRIPT"}]}],""" +
            """"actionItems":[{"t":"do","s":"FRAGMENT"}]}"""
        val s = StructuredSummaryJson.decode(old)!!
        assertFalse(s.sections[0].edited)
        assertFalse(s.sections[0].bullets[0].edited)
        assertFalse(s.actionItems[0].done)
        assertFalse(s.actionItems[0].edited)
    }
}
