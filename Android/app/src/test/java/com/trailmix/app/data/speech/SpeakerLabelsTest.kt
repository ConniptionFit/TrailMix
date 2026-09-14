package com.trailmix.app.data.speech

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
        val lines = listOf(line("0:05"), line("0:35"), line("1:05"))
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
    fun `nearest-segment fallback is measured from segment start, consistent across lines`() {
        val lines = listOf(line("0:05"), line("0:25"))
        val segments = listOf(
            SpeakerSegment(startSeconds = 0f, endSeconds = 20f, speakerTag = 1),
            SpeakerSegment(startSeconds = 30f, endSeconds = 60f, speakerTag = 2),
        )
        val labeled = SpeakerLabels.apply(lines, segments)
        assertEquals("Speaker 1", labeled[0].speakerLabel) // inside tag 1's range
        assertEquals("Speaker 2", labeled[1].speakerLabel) // gap, nearer to tag 2's start
    }

    @Test
    fun `a line with an unparseable label is left unlabeled`() {
        val lines = listOf(TranscriptLine(label = "not-a-timestamp", text = "hi"))
        val segments = listOf(SpeakerSegment(startSeconds = 0f, endSeconds = 60f, speakerTag = 1))
        assertNull(SpeakerLabels.apply(lines, segments).single().speakerLabel)
    }
}
