package com.trailmix.app.data.speech

import com.trailmix.app.data.model.SpeechSource
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerLabelsTest {

    private fun line(label: String, text: String = "line $label") = TranscriptLine(label = label, text = text)

    @Test
    fun `no segments leaves every line unchanged`() {
        val lines = listOf(line("0:05"), line("0:30"))
        assertEquals(lines, SpeakerLabels.apply(lines, emptyList()))
    }

    @Test
    fun `a line lands on the segment whose time range contains it`() {
        val lines = listOf(line("0:05"), line("0:35"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 7),
            SpeakerSegment(startSeconds = 20f, endSeconds = 60f, speakerTag = 2),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel)
        assertEquals("Speaker 2", labeled[1].speakerLabel)
    }

    @Test
    fun `display numbers follow first appearance, not the raw speakerTag value`() {
        val lines = listOf(line("0:05"), line("0:35"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 9),
            SpeakerSegment(startSeconds = 20f, endSeconds = 60f, speakerTag = 0),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel) // tag 9, seen first
        assertEquals("Speaker 2", labeled[1].speakerLabel) // tag 0, seen second
    }

    @Test
    fun `the same speakerTag always maps to the same display label`() {
        val lines = listOf(line("0:05"), line("0:35"), line("1:15"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 1),
            SpeakerSegment(startSeconds = 20f, endSeconds = 60f, speakerTag = 2),
            SpeakerSegment(startSeconds = 60f, endSeconds = 90f, speakerTag = 1),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel)
        assertEquals("Speaker 2", labeled[1].speakerLabel)
        assertEquals("Speaker 1", labeled[2].speakerLabel)
    }

    @Test
    fun `a line in a gap between segments falls back to the nearest one`() {
        // Segment tag 2 starts at 30s, only 5s from this line; tag 1 starts at 0s, 25s away —
        // and since this is the only line resolved, whichever wins is necessarily "Speaker 1"
        // (first display number ever assigned in this call), regardless of its raw tag value.
        val lines = listOf(line("0:25"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 1),
            SpeakerSegment(startSeconds = 30f, endSeconds = 60f, speakerTag = 2),
        )
        val labeled = SpeakerLabels.apply(lines, segments).single()
        assertEquals("Speaker 1", labeled.speakerLabel)
    }

    @Test
    fun `a line is matched over its span, not its end point`() {
        // Speaker 2 starts at 0:28, two seconds before this line is stamped at 0:30, but the
        // line was spoken over 0:18-0:30: nine seconds of it were speaker 1's, so speaker 1 wins.
        val lines = listOf(line("0:10"), line("0:30"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 28f, speakerTag = 1),
            SpeakerSegment(startSeconds = 28f, endSeconds = 60f, speakerTag = 2),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel)
        assertEquals("Speaker 1", labeled[1].speakerLabel)
    }

    @Test
    fun `a span that is a dead heat goes to the speaker talking at the end`() {
        val lines = listOf(line("0:05"), line("0:35"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 7),
            SpeakerSegment(startSeconds = 20f, endSeconds = 60f, speakerTag = 2),
        )
        // The second line's span is 0:23-0:35 (capped), entirely tag 2.
        assertEquals("Speaker 2", SpeakerLabels.apply(lines, segments)[1].speakerLabel)
        val tie = listOf(line("0:20"), line("0:32"))
        val tieSegments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 26f, speakerTag = 7),
            SpeakerSegment(startSeconds = 26f, endSeconds = 60f, speakerTag = 2),
        )
        // Span 0:20-0:32: six seconds each; tag 2 is speaking at 0:32.
        assertEquals("Speaker 2", SpeakerLabels.apply(tie, tieSegments)[1].speakerLabel)
    }

    @Test
    fun `a line whose span touches no segment falls back to the nearest one`() {
        val lines = listOf(line("0:05"), line("0:36"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 10f, speakerTag = 1),
            SpeakerSegment(startSeconds = 40f, endSeconds = 60f, speakerTag = 2),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel)
        assertEquals("Speaker 2", labeled[1].speakerLabel) // 0:36 is 4s from tag 2's start
    }

    @Test
    fun `the cluster the Me lane lines fall in is named Me and the rest renumber from 1`() {
        val lines = listOf(
            TranscriptLine("0:12", "a", speechSource = SpeechSource.THEM),
            TranscriptLine("0:30", "b", speechSource = SpeechSource.ME),
            TranscriptLine("0:42", "c", speechSource = SpeechSource.ME),
            TranscriptLine("0:50", "d"),
            TranscriptLine("1:05", "e", speechSource = SpeechSource.THEM),
        )
        val segments = listOf(
            SpeakerSegment(0f, 15f, 4),
            SpeakerSegment(15f, 45f, 9),
            SpeakerSegment(45f, 55f, 9),
            SpeakerSegment(55f, 90f, 4),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals(
            listOf("Speaker 1", "Me", "Me", "Me", "Speaker 1"),
            labeled.map { it.speakerLabel },
        )
    }

    @Test
    fun `one stray Me line does not name a cluster Me`() {
        val lines = listOf(
            TranscriptLine("0:10", "a", speechSource = SpeechSource.ME),
            TranscriptLine("0:30", "b"),
        )
        val segments = listOf(SpeakerSegment(0f, 60f, 3))
        assertEquals("Speaker 1", SpeakerLabels.apply(lines, segments)[0].speakerLabel)
    }

    @Test
    fun `two clusters that are both Me-heavy name neither Me`() {
        val lines = listOf(
            TranscriptLine("0:10", "a", speechSource = SpeechSource.ME),
            TranscriptLine("0:20", "b", speechSource = SpeechSource.ME),
            TranscriptLine("0:40", "c", speechSource = SpeechSource.ME),
            TranscriptLine("0:50", "d", speechSource = SpeechSource.ME),
        )
        val segments = listOf(SpeakerSegment(0f, 25f, 1), SpeakerSegment(25f, 60f, 2))
        assertEquals(listOf("Speaker 1", "Speaker 1", "Speaker 2", "Speaker 2"), SpeakerLabels.apply(lines, segments).map { it.speakerLabel })
    }

    @Test
    fun `shift moves segments onto the transcript timeline`() {
        val shifted = SpeakerLabels.shift(listOf(SpeakerSegment(0f, 10f, 1)), 120f)
        assertEquals(SpeakerSegment(120f, 130f, 1), shifted.single())
        val same = listOf(SpeakerSegment(0f, 10f, 1))
        assertEquals(same, SpeakerLabels.shift(same, 0f))
    }

    @Test
    fun `a resumed session labels lines on the continued timeline`() {
        // Audio retention began at 2:00; the first retained speaker turn is at its 0:00.
        val lines = listOf(line("2:08"), line("2:40"))
        val segments = SpeakerLabels.shift(
            listOf(SpeakerSegment(0f, 15f, 5), SpeakerSegment(15f, 60f, 6)),
            120f,
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel)
        assertEquals("Speaker 2", labeled[1].speakerLabel)
    }

    @Test
    fun `a line with an unparseable label is left unlabeled`() {
        val lines = listOf(TranscriptLine(label = "not-a-timestamp", text = "hi"))
        val segments = listOf(SpeakerSegment(startSeconds = 0f, endSeconds = 60f, speakerTag = 1))
        assertNull(SpeakerLabels.apply(lines, segments).single().speakerLabel)
    }
}
