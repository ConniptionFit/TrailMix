package com.trailmix.app.data.ai

import com.trailmix.app.data.ai.RegeneratePolicy.Refusal
import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegeneratePolicyTest {
    private val line = listOf(TranscriptLine("0:04", "Token lifetimes first."))

    private fun refusal(
        recording: Boolean = false,
        paused: Boolean = false,
        merging: Boolean = false,
        recovering: Boolean = false,
        typed: String = "",
        transcript: List<TranscriptLine> = line,
    ) = RegeneratePolicy.refusal(recording, paused, merging, recovering, typed, transcript)

    @Test
    fun `idle note with a transcript may regenerate`() = assertNull(refusal())

    @Test
    fun `typed notes alone are enough`() = assertNull(refusal(typed = "rough", transcript = emptyList()))

    @Test
    fun `every live state refuses as busy`() {
        assertEquals(Refusal.BUSY, refusal(recording = true))
        assertEquals(Refusal.BUSY, refusal(paused = true))
        assertEquals(Refusal.BUSY, refusal(merging = true))
        assertEquals(Refusal.BUSY, refusal(recovering = true))
    }

    @Test
    fun `an empty note has nothing to merge`() {
        assertEquals(Refusal.NOTHING_TO_MERGE, refusal(typed = "  ", transcript = emptyList()))
    }

    @Test
    fun `busy wins over nothing-to-merge`() {
        assertEquals(Refusal.BUSY, refusal(merging = true, transcript = emptyList()))
    }

    @Test
    fun `edits to replace combine a flat body and structured edits`() {
        assertEquals(0, RegeneratePolicy.editsToReplace(null, 0))
        assertEquals(1, RegeneratePolicy.editsToReplace("edited", 0))
        assertEquals(4, RegeneratePolicy.editsToReplace("edited", 3))
        assertEquals(3, RegeneratePolicy.editsToReplace(null, 3))
    }

    @Test
    fun `hand edits need a confirm, a clean note does not`() {
        assertTrue(RegeneratePolicy.needsOverwriteConfirm("edited"))
        assertTrue(RegeneratePolicy.needsOverwriteConfirm(""))
        assertFalse(RegeneratePolicy.needsOverwriteConfirm(null))
    }

    @Test
    fun `fragmentsChanged ignores trailing whitespace only`() {
        assertFalse(RegeneratePolicy.fragmentsChanged("a\nb", "a\nb\n\n"))
        assertTrue(RegeneratePolicy.fragmentsChanged("a", "a b"))
    }

    @Test
    fun `failure messages are distinct`() {
        val all = listOf(Refusal.BUSY, Refusal.NOTHING_TO_MERGE, null).map(RegeneratePolicy::failureMessage)
        assertEquals(3, all.toSet().size)
    }
}
