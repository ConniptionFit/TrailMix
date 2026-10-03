package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceprintsTest {

    private fun unit(vararg v: Float) = VoiceMath.normalized(floatArrayOf(*v))!!
    private fun person(id: String, name: String, vararg prints: FloatArray, isMe: Boolean = false) =
        VoicePerson(id, name, isMe, prints.map { Voiceprint(it, 5.0, 0L) })

    // Directions in 3-d: same-speaker vectors are close, different ones far apart.
    private val priya = unit(1f, 0.1f, 0f)
    private val jack = unit(0f, 1f, 0.1f)
    private val dana = unit(0.1f, 0f, 1f)

    @Test
    fun `cosine of identical vectors is 1 and of orthogonal is 0`() {
        assertEquals(1f, VoiceMath.cosine(priya, priya), 1e-5f)
        assertEquals(0f, VoiceMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun `cosine treats mismatched or zero vectors as no evidence`() {
        assertEquals(0f, VoiceMath.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)), 0f)
        assertEquals(0f, VoiceMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0f)
    }

    @Test
    fun `a weighted mean leans toward the heavier sample and is unit length`() {
        val mean = VoiceMath.weightedMean(listOf(floatArrayOf(1f, 0f) to 9.0, floatArrayOf(0f, 1f) to 1.0))!!
        assertTrue(mean[0] > mean[1])
        assertEquals(1f, VoiceMath.cosine(mean, mean), 1e-5f)
        assertEquals(1.0, mean.sumOf { (it * it).toDouble() }, 1e-5)
    }

    @Test
    fun `a weighted mean of nothing usable is null`() {
        assertNull(VoiceMath.weightedMean(emptyList()))
        assertNull(VoiceMath.weightedMean(listOf(floatArrayOf(0f, 0f) to 1.0)))
        assertNull(VoiceMath.weightedMean(listOf(floatArrayOf(1f, 0f) to 1.0, floatArrayOf(1f) to 1.0)))
    }

    @Test
    fun `the closest enrolled person wins with a margin`() {
        val people = listOf(person("p", "Priya", priya), person("j", "Jack", jack))
        val m = VoiceMatcher.match(unit(1f, 0.15f, 0f), people)!!
        assertEquals("Priya", m.name)
        assertTrue(m.similarity > 0.99f)
        assertTrue(m.margin > 0.5f)
        assertEquals(SpeakerFusion.CONFIDENT_SCORE, m.tierScore, 0.0)
    }

    @Test
    fun `a voice nobody resembles matches nobody`() {
        assertNull(VoiceMatcher.match(dana, listOf(person("p", "Priya", priya), person("j", "Jack", jack))))
        assertNull(VoiceMatcher.match(priya, emptyList()))
    }

    @Test
    fun `a match with a thin margin is only a suggestion`() {
        val twinA = unit(1f, 0.30f, 0f)
        val twinB = unit(1f, 0.38f, 0f)
        val m = VoiceMatcher.match(unit(1f, 0.34f, 0f), listOf(person("a", "A", twinA), person("b", "B", twinB)))!!
        assertEquals(SpeakerFusion.SUGGESTED_SCORE, m.tierScore, 0.0)
    }

    @Test
    fun `a person with several channel prints matches on the best one`() {
        val phone = priya
        val speaker = unit(0.2f, 0.2f, 1f)
        val p = person("p", "Priya", phone, speaker)
        assertTrue(VoiceMatcher.similarityTo(p, unit(0.2f, 0.2f, 0.95f)) > 0.9f)
    }

    @Test
    fun `two speakers never get the same person`() {
        val people = listOf(person("p", "Priya", priya), person("j", "Jack", jack))
        val result = VoiceMatcher.matchAll(mapOf("Speaker 1" to priya, "Speaker 2" to unit(0.9f, 0.3f, 0f)), people)
        assertEquals("Priya", result["Speaker 1"]!!.name)
        assertTrue("Speaker 2" !in result || result["Speaker 2"]!!.name != "Priya")
    }

    @Test
    fun `a sample close to a print refines it`() {
        val p = person("p", "Priya", priya)
        val updated = VoiceMatcher.withSample(p, unit(1f, 0.2f, 0f), 5.0, 10L)
        assertEquals(1, updated.prints.size)
        assertEquals(10.0, updated.prints.single().weight, 1e-9)
        assertEquals(10L, updated.prints.single().updatedAtMs)
    }

    @Test
    fun `a sample from a different channel becomes a new print, and the weakest goes past the cap`() {
        var p = person("p", "Priya", priya)
        p = VoiceMatcher.withSample(p, jack, 1.0, 1L)
        assertEquals(2, p.prints.size)
        p = VoiceMatcher.withSample(p, dana, 1.0, 2L)
        p = VoiceMatcher.withSample(p, unit(-1f, 0f, 0f), 1.0, 3L)
        p = VoiceMatcher.withSample(p, unit(0f, -1f, 0f), 9.0, 4L)
        assertEquals(VoiceMatcher.MAX_PRINTS_PER_PERSON, p.prints.size)
        assertTrue(p.prints.any { it.weight == 9.0 })
    }

    @Test
    fun `a print's weight is capped so it keeps adapting`() {
        var p = person("p", "Priya", priya)
        repeat(10) { p = VoiceMatcher.withSample(p, priya, 20.0, it.toLong()) }
        assertEquals(VoiceMatcher.MAX_WEIGHT, p.prints.single().weight, 1e-9)
    }

    @Test
    fun `an unusable sample changes nothing`() {
        val p = person("p", "Priya", priya)
        assertEquals(p.prints.size, VoiceMatcher.withSample(p, floatArrayOf(0f, 0f, 0f), 5.0, 1L).prints.size)
        assertEquals(1.0, VoiceMatcher.withSample(p, priya, 0.0, 1L).prints.single().weight - 4.0, 1e-9)
    }

    @Test
    fun `people survive a JSON round trip including embeddings`() {
        val people = listOf(person("p", "Priya", priya, jack), person("me", "Sam", dana, isMe = true))
        val back = VoicePeopleJson.decode(VoicePeopleJson.encode(people))
        assertEquals(listOf("Priya", "Sam"), back.map { it.name })
        assertTrue(back[1].isMe)
        assertEquals(2, back[0].prints.size)
        assertEquals(1f, VoiceMath.cosine(back[0].prints[0].centroid, priya), 1e-6f)
        assertEquals(5.0, back[0].prints[0].weight, 0.0)
    }

    @Test
    fun `decoding junk is null, and an empty list is not junk`() {
        assertNull(VoicePeopleJson.decodeOrNull("not json"))
        assertNull(VoicePeopleJson.decodeOrNull("{\"other\":1}"))
        assertEquals(emptyList<VoicePerson>(), VoicePeopleJson.decodeOrNull(VoicePeopleJson.encode(emptyList())))
        assertEquals(emptyList<VoicePerson>(), VoicePeopleJson.decode("not json"))
    }

    @Test
    fun `note centroids round trip by label`() {
        val back = VoicePeopleJson.decodeCentroids(VoicePeopleJson.encodeCentroids(mapOf("Speaker 2" to priya, "Jack Lee" to jack)))
        assertEquals(setOf("Speaker 2", "Jack Lee"), back.keys)
        assertEquals(1f, VoiceMath.cosine(back.getValue("Speaker 2"), priya), 1e-6f)
    }

    @Test
    fun `withVoice creates a person once and then refines them`() {
        var ids = 0
        val first = VoicePeople.withVoice(emptyList(), "  Priya ", false, priya, 5.0, 1L) { "id${ids++}" }
        assertEquals("Priya", first.single().name)
        val again = VoicePeople.withVoice(first, "priya", false, unit(1f, 0.2f, 0f), 5.0, 2L) { "id${ids++}" }
        assertEquals(1, again.size)
        assertEquals(1, ids)
        assertEquals(10.0, again.single().prints.single().weight, 1e-9)
    }

    @Test
    fun `the note-taker is a single person whatever name they have now`() {
        var n = 0
        val first = VoicePeople.withVoice(emptyList(), "Sam", true, priya, 5.0, 1L) { "id${n++}" }
        val renamed = VoicePeople.withVoice(first, "Samantha", true, priya, 5.0, 2L) { "id${n++}" }
        assertEquals(1, renamed.size)
        assertTrue(renamed.single().isMe)
    }

    @Test
    fun `a blank name or unusable voice enrolls nobody`() {
        assertTrue(VoicePeople.withVoice(emptyList(), "  ", false, priya, 5.0, 1L) { "x" }.isEmpty())
        assertTrue(VoicePeople.withVoice(emptyList(), "Priya", false, floatArrayOf(0f, 0f), 5.0, 1L) { "x" }.isEmpty())
    }

    @Test
    fun `final labels follow the lines by position`() {
        val pre = listOf(
            TranscriptLine("0:01", "a", speakerLabel = "Speaker 1"),
            TranscriptLine("0:02", "b", speakerLabel = "Speaker 2"),
            TranscriptLine("0:03", "c", speakerLabel = "Speaker 1"),
            TranscriptLine("0:04", "d"),
        )
        val post = pre.map { if (it.speakerLabel == "Speaker 1") it.copy(speakerLabel = "Priya Patel") else it }
        assertEquals(mapOf("Speaker 1" to "Priya Patel", "Speaker 2" to "Speaker 2"), VoicePeople.finalLabels(pre, post))
    }

    private fun meLine(i: Int, src: SpeechSource?) = TranscriptLine("0:%02d".format(i), "x", speakerLabel = "Me", speechSource = src)

    @Test
    fun `Me enrollment needs enough lines of nearly pure lane evidence`() {
        val pure = (1..5).map { meLine(it, SpeechSource.ME) }
        assertEquals(5.0, MeEnrollment.weight(pure), 0.0)
        assertEquals(0.0, MeEnrollment.weight(pure.take(3)), 0.0)
        val muddy = (1..5).map { meLine(it, if (it <= 3) SpeechSource.ME else SpeechSource.THEM) }
        assertEquals(0.0, MeEnrollment.weight(muddy), 0.0)
        val unlabelled = (1..5).map { meLine(it, null) }
        assertEquals(0.0, MeEnrollment.weight(unlabelled), 0.0)
    }

    @Test
    fun `Me enrollment weight is capped`() {
        assertEquals(MeEnrollment.MAX_WEIGHT, MeEnrollment.weight((1..50).map { meLine(it, SpeechSource.ME) }), 0.0)
    }

    @Test
    fun `a match carries whether it is the note-taker`() {
        val m = VoiceMatcher.match(priya, listOf(person("m", "Sam", priya, isMe = true)))
        assertNotNull(m)
        assertTrue(m!!.isMe)
    }
}
