package com.trailmix.app.data.ai

import com.trailmix.app.data.ai.NoteAnchors.Anchor
import com.trailmix.app.data.ai.NoteAnchors.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteAnchorsTest {

    @Test
    fun `blank input yields no anchors`() {
        assertTrue(NoteAnchors.parse("").isEmpty())
        assertTrue(NoteAnchors.parse("  \n \n").isEmpty())
        assertTrue(NoteAnchors.parse("-\n*\n#").isEmpty())
    }

    @Test
    fun `bullets and plain lines are points with markers stripped, in order`() {
        val anchors = NoteAnchors.parse("- confirm ICP alignment\n* Deal stalls - sales input\n•  budget   approved\nplain line")
        assertEquals(
            listOf(
                Anchor(Kind.POINT, "confirm ICP alignment"),
                Anchor(Kind.POINT, "Deal stalls - sales input"),
                Anchor(Kind.POINT, "budget approved"),
                Anchor(Kind.POINT, "plain line"),
            ),
            anchors,
        )
    }

    @Test
    fun `hash lines are headings in title case`() {
        val anchors = NoteAnchors.parse("## q3 messaging rollout\n# plan of the ARR push")
        assertEquals(Anchor(Kind.HEADING, "Q3 Messaging Rollout"), anchors[0])
        // Minor words stay lower; an interior-capital word keeps its typed form.
        assertEquals(Anchor(Kind.HEADING, "Plan of the ARR Push"), anchors[1])
    }

    @Test
    fun `question mark makes a question even with a judgment cue`() {
        val anchors = NoteAnchors.parse("ARR?\nwhy does churn seem high?")
        assertEquals(listOf(Kind.QUESTION, Kind.QUESTION), anchors.map { it.kind })
    }

    @Test
    fun `judgment cues are detected`() {
        val anchors = NoteAnchors.parse(
            "I don't buy the 2wk estimate\nchurn seems high\nworried about the timeline\nnot convinced by the demo",
        )
        assertTrue(anchors.all { it.kind == Kind.JUDGMENT })
    }

    @Test
    fun `curly apostrophe still matches a cue`() {
        assertTrue(NoteAnchors.isJudgment("I don’t buy it"))
    }

    @Test
    fun `ordinary statements are not judgments`() {
        assertFalse(NoteAnchors.isJudgment("founder ex-Stripe"))
        assertFalse(NoteAnchors.isJudgment("I think we ship Friday"))
        assertFalse(NoteAnchors.isJudgment("wants more design exposure"))
    }

    @Test
    fun `text without line breaks is split into sentences`() {
        val anchors = NoteAnchors.parse("Budget approved. Is legal done? Seems risky.")
        assertEquals(
            listOf(
                Anchor(Kind.POINT, "Budget approved."),
                Anchor(Kind.QUESTION, "Is legal done?"),
                Anchor(Kind.JUDGMENT, "Seems risky."),
            ),
            anchors,
        )
    }

    @Test
    fun `granola 1-1 example classifies each note`() {
        val anchors = NoteAnchors.parse("- onboarding proj slipping?\n- wants more design exposure\n- I don't buy the 2wk estimate")
        assertEquals(listOf(Kind.QUESTION, Kind.POINT, Kind.JUDGMENT), anchors.map { it.kind })
    }
}
