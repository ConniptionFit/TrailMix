package com.trailmix.app.ui.export

import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeHandoffTest {
    @Test
    fun messageKeepsTheNoteVerbatimAfterTheInstruction() {
        val note = "# Title\n\n- point"
        val text = ClaudeHandoff.message(note)
        assertTrue(text.endsWith(note))
        assertTrue(text.startsWith("Here are my meeting notes"))
    }
}
