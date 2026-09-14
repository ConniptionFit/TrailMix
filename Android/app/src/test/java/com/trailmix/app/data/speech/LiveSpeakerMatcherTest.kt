package com.trailmix.app.data.speech

import com.trailmix.app.data.speech.LiveSpeakerMatcher.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSpeakerMatcherTest {

    private fun frame(vararg scores: Float) = floatArrayOf(*scores)
    private val empty = floatArrayOf()

    @Test
    fun `no frames at all is insufficient signal`() {
        assertEquals(Verdict.InsufficientSignal, LiveSpeakerMatcher.resolve(emptyList()))
    }

    @Test
    fun `fewer usable frames than minVotes is insufficient signal`() {
        val frames = listOf(frame(0.9f, 0.1f), frame(0.9f, 0.1f)) // 2 frames, default minVotes = 3
        assertEquals(Verdict.InsufficientSignal, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `a confident consistent match resolves to that profile`() {
        val frames = List(4) { frame(0.9f, 0.1f) }
        assertEquals(Verdict.Recognized(0), LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `scores that never clear the threshold resolve to Unknown`() {
        val frames = listOf(frame(0.4f, 0.3f), frame(0.45f, 0.2f), frame(0.5f, 0.1f))
        assertEquals(Verdict.Unknown, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `a tie between two profiles at or past minVotes is Unknown`() {
        val frames = listOf(
            frame(0.9f, 0.1f), frame(0.9f, 0.1f), frame(0.9f, 0.1f), // 3 votes for profile 0
            frame(0.1f, 0.9f), frame(0.1f, 0.9f), frame(0.1f, 0.9f), // 3 votes for profile 1
        )
        assertEquals(Verdict.Unknown, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `a tie below minVotes is insufficient signal, not Unknown`() {
        val frames = listOf(
            frame(0.9f, 0.1f), frame(0.9f, 0.1f), // 2 votes for profile 0
            frame(0.1f, 0.9f), frame(0.1f, 0.9f), // 2 votes for profile 1
        ) // default minVotes = 3, neither leader clears it
        assertEquals(Verdict.InsufficientSignal, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `a single noisy dip does not flip a confident majority`() {
        val frames = listOf(
            frame(0.9f, 0.1f), frame(0.9f, 0.1f), frame(0.9f, 0.1f), frame(0.9f, 0.1f),
            frame(0.1f, 0.9f), // one dissenting frame
        )
        assertEquals(Verdict.Recognized(0), LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `frames with no usable voice are skipped, not counted against the vote requirement`() {
        val frames = listOf(empty, frame(0.9f, 0.1f), empty, frame(0.9f, 0.1f), frame(0.9f, 0.1f), empty)
        assertEquals(Verdict.Recognized(0), LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `all-empty frames are insufficient signal, not Unknown`() {
        val frames = listOf(empty, empty, empty, empty)
        assertEquals(Verdict.InsufficientSignal, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `raising the threshold turns a would-be match into Unknown`() {
        val frames = List(3) { frame(0.5f, 0.1f) }
        assertEquals(Verdict.Unknown, LiveSpeakerMatcher.resolve(frames, matchThreshold = 0.6f))
    }

    @Test
    fun `lowering the threshold turns that same data into a recognized match`() {
        val frames = List(3) { frame(0.5f, 0.1f) }
        assertEquals(Verdict.Recognized(0), LiveSpeakerMatcher.resolve(frames, matchThreshold = 0.4f))
    }

    @Test
    fun `default minVotes rejects two confident frames`() {
        val frames = List(2) { frame(0.9f, 0.1f) }
        assertEquals(Verdict.InsufficientSignal, LiveSpeakerMatcher.resolve(frames))
    }

    @Test
    fun `a lower custom minVotes accepts that same pair of frames`() {
        val frames = List(2) { frame(0.9f, 0.1f) }
        assertEquals(Verdict.Recognized(0), LiveSpeakerMatcher.resolve(frames, minVotes = 2))
    }

    @Test
    fun `the second profile can win just as cleanly as the first`() {
        val frames = List(3) { frame(0.1f, 0.9f) }
        assertEquals(Verdict.Recognized(1), LiveSpeakerMatcher.resolve(frames))
    }
}
