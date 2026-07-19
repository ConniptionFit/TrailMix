package com.trailmix.app.data.ai

import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAP-11: the empty-capture save policy. */
class MergePolicyTest {

    @Test
    fun `nothing typed and nothing transcribed - nothing to save`() {
        assertTrue(MergePolicy.nothingToSave("", emptyList()))
        assertTrue(MergePolicy.nothingToSave("   \n  ", emptyList()))
    }

    @Test
    fun `blank-only transcript lines count as no transcript`() {
        val blankLines = listOf(TranscriptLine("00:01", ""), TranscriptLine("00:02", "   "))
        assertFalse(MergePolicy.hasTranscript(blankLines))
        assertTrue(MergePolicy.nothingToSave("", blankLines))
    }

    @Test
    fun `typed notes alone are worth saving - but there is no transcript to summarize`() {
        assertFalse(MergePolicy.nothingToSave("bought milk", emptyList()))
        assertFalse(MergePolicy.hasTranscript(emptyList()))
    }

    @Test
    fun `any real transcript line means save and summarize`() {
        val transcript = listOf(TranscriptLine("00:01", "hello world"))
        assertTrue(MergePolicy.hasTranscript(transcript))
        assertFalse(MergePolicy.nothingToSave("", transcript))
        assertFalse(MergePolicy.nothingToSave("typed too", transcript))
    }
}
