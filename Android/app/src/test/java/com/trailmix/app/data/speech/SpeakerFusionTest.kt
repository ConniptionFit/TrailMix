package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerFusionTest {

    private fun spk(n: Int, text: String, t: String = "0:00") = TranscriptLine(t, text, speakerLabel = "Speaker $n")
    private fun me(text: String, t: String = "0:00") = TranscriptLine(t, text, speakerLabel = "Me")
    private fun them(text: String, t: String = "0:00") = TranscriptLine(t, text, speechSource = SpeechSource.THEM)

    private val roster3 = listOf("Priya Patel", "Jack Lee", "Dana Wu")
    private val roster2 = listOf("Priya Patel", "Jack Lee")

    @Test
    fun `no roster leaves the transcript untouched`() {
        val lines = listOf(spk(1, "this is Priya"))
        assertEquals(lines, SpeakerFusion.apply(lines, emptyList(), null))
    }

    @Test
    fun `a self introduction names the speaker plainly`() {
        val lines = listOf(spk(1, "Hi all, this is Priya"), spk(2, "Hello"), spk(1, "Let's start"))
        val named = SpeakerFusion.apply(lines, roster3, null)
        assertEquals(listOf("Priya Patel", "Speaker 2", "Priya Patel"), named.map { it.speakerLabel })
    }

    @Test
    fun `being asked a question names the next different speaker, with a question mark`() {
        val lines = listOf(spk(1, "Jack, what's the status"), spk(2, "We are on track"))
        val named = SpeakerFusion.apply(lines, roster3, null)
        assertEquals("Speaker 1", named[0].speakerLabel)
        assertEquals("Speaker 2 (Jack Lee?)", named[1].speakerLabel)
    }

    @Test
    fun `two agreeing hand-offs reach confident`() {
        val lines = listOf(
            spk(1, "Jack, what's the status", "0:10"), spk(2, "On track", "0:20"),
            spk(1, "Let's hear from Jack on risks", "0:30"), spk(2, "One risk", "0:40"),
        )
        val named = SpeakerFusion.apply(lines, roster3, null)
        assertEquals("Jack Lee", named[1].speakerLabel)
        assertEquals("Jack Lee", named[3].speakerLabel)
    }

    @Test
    fun `thanks names the previous speaker, not the next one`() {
        val lines = listOf(spk(2, "Here is the update"), spk(1, "Thanks Dana"), spk(1, "Next topic"))
        val named = SpeakerFusion.apply(lines, roster3, null)
        assertEquals("Speaker 2 (Dana Wu?)", named[0].speakerLabel)
        assertEquals("Speaker 1", named[1].speakerLabel)
    }

    @Test
    fun `conflicting evidence for the same speaker names nobody`() {
        val lines = listOf(spk(1, "this is Priya"), spk(1, "and this is Jack"))
        val named = SpeakerFusion.apply(lines, roster3, null)
        // Priya 3.0 vs Jack 3.0: no margin.
        assertEquals(listOf("Speaker 1", "Speaker 1"), named.map { it.speakerLabel })
    }

    @Test
    fun `no name is given to two speakers`() {
        val lines = listOf(spk(1, "this is Priya"), spk(2, "this is Priya"))
        val named = SpeakerFusion.apply(lines, roster3, null).map { it.speakerLabel }
        assertEquals(1, named.count { it == "Priya Patel" })
    }

    @Test
    fun `the note-taker's cluster is never renamed`() {
        val lines = listOf(me("this is Jack"), spk(1, "Hello"))
        val named = SpeakerFusion.apply(lines, roster3, "Priya Patel")
        assertEquals("Me", named[0].speakerLabel)
    }

    @Test
    fun `a one-on-one names the other speaker outright`() {
        val lines = listOf(me("Hello"), spk(1, "Hi there"), me("Let's go"))
        val named = SpeakerFusion.apply(lines, roster2, "Priya Patel")
        assertEquals(listOf("Me", "Jack Lee", "Me"), named.map { it.speakerLabel })
    }

    @Test
    fun `a one-on-one from the lane alone names Them`() {
        val lines = listOf(
            TranscriptLine("0:01", "Hello", speechSource = SpeechSource.ME),
            them("Hi there"),
        )
        val named = SpeakerFusion.apply(lines, roster2, "Priya Patel")
        assertEquals("Jack Lee", named[1].speakerLabel)
        assertEquals(SpeechSource.ME, named[0].speechSource)
    }

    @Test
    fun `a one-on-one needs the note-taker to be known`() {
        val lines = listOf(me("Hello"), spk(1, "Hi there"))
        assertEquals(lines, SpeakerFusion.apply(lines, roster2, null))
        assertEquals(lines, SpeakerFusion.apply(lines, roster2, "Somebody Else"))
    }

    @Test
    fun `a one-on-one is not assumed when two other speakers are present`() {
        val lines = listOf(me("Hello"), spk(1, "Hi"), spk(2, "Hey"))
        assertEquals(lines, SpeakerFusion.apply(lines, roster2, "Priya Patel"))
    }

    @Test
    fun `Them stays anonymous in a bigger meeting even with a cue`() {
        val lines = listOf(me("Jack, can you start"), them("Sure"))
        val named = SpeakerFusion.apply(lines, roster3, "Priya Patel")
        assertTrue(named[1].speakerLabel == null)
    }

    @Test
    fun `running twice changes nothing more`() {
        val lines = listOf(me("Hello"), spk(1, "Hi there"))
        val once = SpeakerFusion.apply(lines, roster2, "Priya Patel")
        assertEquals(once, SpeakerFusion.apply(once, roster2, "Priya Patel"))
    }

    @Test
    fun `an assignment reads as a plain name only when confident`() {
        assertEquals("Jack", SpeakerFusion.Assignment("Speaker 1", "Jack", 3.0).display)
        assertEquals("Speaker 1 (Jack?)", SpeakerFusion.Assignment("Speaker 1", "Jack", 1.5).display)
    }

    private fun match(name: String, sim: Float = 0.8f, margin: Float = 0.3f, isMe: Boolean = false) =
        VoiceMatch("id-$name", name, sim, margin, isMe)

    @Test
    fun `a confident voice match for a rostered name is a plain name`() {
        val lines = listOf(spk(1, "Hello"), spk(2, "Hi"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 2" to match("Jack Lee")))
        assertEquals(listOf("Speaker 1", "Jack Lee"), named.map { it.speakerLabel })
    }

    @Test
    fun `a weak voice match is a question`() {
        val lines = listOf(spk(1, "Hello"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 1" to match("Jack Lee", sim = 0.55f, margin = 0.02f)))
        assertEquals("Speaker 1 (Jack Lee?)", named.single().speakerLabel)
    }

    @Test
    fun `voice and a cue for the same name add up`() {
        val lines = listOf(spk(1, "Jack, what's the status"), spk(2, "On track"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 2" to match("Jack Lee", sim = 0.55f, margin = 0.02f)))
        assertEquals("Jack Lee", named[1].speakerLabel)
    }

    @Test
    fun `a voice that disagrees with a self introduction names nobody`() {
        val lines = listOf(spk(1, "Hi this is Priya"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 1" to match("Dana Wu")))
        assertEquals("Speaker 1", named.single().speakerLabel)
    }

    @Test
    fun `an enrolled guest who is not on the calendar is named by voice alone`() {
        val lines = listOf(spk(1, "Hello"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 1" to match("Alex Guest")))
        assertEquals("Alex Guest", named.single().speakerLabel)
    }

    @Test
    fun `an enrolled guest works with no calendar at all`() {
        val lines = listOf(spk(1, "Hello"))
        val named = SpeakerFusion.apply(lines, emptyList(), null, mapOf("Speaker 1" to match("Alex Guest")))
        assertEquals("Alex Guest", named.single().speakerLabel)
    }

    @Test
    fun `the note-taker's voice relabels a cluster as Me when the lane could not`() {
        val lines = listOf(spk(1, "Hello"), spk(2, "Hi"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 2" to match("Sam", isMe = true)))
        assertEquals(listOf("Speaker 1", "Me"), named.map { it.speakerLabel })
    }

    @Test
    fun `the note-taker's voice does not override an existing Me`() {
        val lines = listOf(me("Hello"), spk(1, "Hi"))
        val named = SpeakerFusion.apply(lines, roster3, null, mapOf("Speaker 1" to match("Sam", isMe = true)))
        assertEquals(listOf("Me", "Speaker 1"), named.map { it.speakerLabel })
    }

    @Test
    fun `a voice match never names the Them lane`() {
        val lines = listOf(me("Hi"), them("Hello"))
        val named = SpeakerFusion.apply(lines, roster3, "Priya Patel", mapOf("Them" to match("Jack Lee")))
        assertTrue(named[1].speakerLabel == null)
    }

    @Test
    fun `one enrolled name is not given to two speakers`() {
        val lines = listOf(spk(1, "Hello"), spk(2, "Hi"))
        val named = SpeakerFusion.apply(
            lines, roster3, null,
            mapOf("Speaker 1" to match("Alex Guest"), "Speaker 2" to match("alex guest", sim = 0.6f)),
        )
        assertEquals(1, named.count { it.speakerLabel == "Alex Guest" })
    }
}
