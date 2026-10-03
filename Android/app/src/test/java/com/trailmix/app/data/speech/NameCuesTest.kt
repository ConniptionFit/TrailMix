package com.trailmix.app.data.speech

import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.speech.NameCues.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameCuesTest {

    private val roster = Roster.parse(listOf("Priya Patel", "Jack Lee", "Rob Smith", "Me Myself"), null)
    private val priya = 0
    private val jack = 1
    private val rob = 2

    private fun cues(vararg text: String) =
        NameCues.extract(text.mapIndexed { i, t -> TranscriptLine("0:%02d".format(i), t) }, roster)

    @Test
    fun `this is Priya is a self introduction`() {
        assertEquals(listOf(NameCues.Cue(0, priya, Kind.SELF_INTRO)), cues("Hi everyone, this is Priya from design"))
    }

    @Test
    fun `my name is and I'm both introduce`() {
        assertEquals(Kind.SELF_INTRO, cues("My name is Jack").single().kind)
        assertEquals(Kind.SELF_INTRO, cues("I'm Jack and I lead the platform team").single().kind)
    }

    @Test
    fun `name here introduces`() {
        assertEquals(Kind.SELF_INTRO, cues("Priya here, can everyone hear me").single().kind)
    }

    @Test
    fun `an introduction late in a line is introducing someone else`() {
        assertTrue(cues("We talked about the launch plan for a while and then this is Priya").none { it.kind == Kind.SELF_INTRO })
    }

    @Test
    fun `an everyday-word name only counts behind this is or my name is`() {
        assertTrue(cues("I'm Rob and I will take it").isEmpty())
        assertEquals(Kind.SELF_INTRO, cues("Hello, this is Rob").single().kind)
        assertTrue(cues("Rob, can you take that").isEmpty())
    }

    @Test
    fun `a name followed by a comma at the start addresses the next speaker`() {
        assertEquals(listOf(NameCues.Cue(0, priya, Kind.ADDRESS_NEXT)), cues("Priya, what do you think about the timeline"))
        assertEquals(Kind.ADDRESS_NEXT, cues("Okay Jack can you walk us through it").single().kind)
    }

    @Test
    fun `hand-off phrases address the next speaker`() {
        assertEquals(Kind.ADDRESS_NEXT, cues("Let's hear from Jack").single().kind)
        assertEquals(Kind.ADDRESS_NEXT, cues("Over to Priya").single().kind)
    }

    @Test
    fun `thanks points at the previous speaker`() {
        assertEquals(listOf(NameCues.Cue(0, jack, Kind.THANKED_PREVIOUS)), cues("Thanks Jack"))
        assertEquals(Kind.THANKED_PREVIOUS, cues("Thank you so much Priya").single().kind)
    }

    @Test
    fun `thanks and a question in one breath give both cues`() {
        val kinds = cues("Thanks Priya, can you also cover pricing").map { it.kind }.toSet()
        assertEquals(setOf(Kind.THANKED_PREVIOUS, Kind.ADDRESS_NEXT), kinds)
    }

    @Test
    fun `a third-person mention is no cue`() {
        assertTrue(cues("Priya said the launch moved to Friday").isEmpty())
        assertTrue(cues("I talked to Jack yesterday about the budget").isEmpty())
    }

    @Test
    fun `the note-taker's own name gives no cue`() {
        val me = Roster.parse(listOf("Priya Patel", "Jack Lee"), "Priya Patel")
        assertTrue(NameCues.extract(listOf(TranscriptLine("0:01", "Thanks Priya")), me).isEmpty())
    }

    @Test
    fun `curly apostrophes and case do not matter`() {
        assertEquals(Kind.SELF_INTRO, cues("I’m PRIYA, nice to meet you").single().kind)
    }

    @Test
    fun `no roster means no cues`() {
        assertTrue(NameCues.extract(listOf(TranscriptLine("0:01", "this is Priya")), Roster.EMPTY).isEmpty())
    }
}
