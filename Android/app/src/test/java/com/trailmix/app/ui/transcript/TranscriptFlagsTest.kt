package com.trailmix.app.ui.transcript

import com.trailmix.app.data.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptFlagsTest {

    private fun line(label: String, text: String = "line $label") = TranscriptLine(label = label, text = text)

    @Test
    fun `a flag lands on the last line at or before its own timestamp`() {
        val lines = listOf(line("0:05"), line("0:30"), line("1:00"), line("1:45"))

        assertEquals(2, flagLineIndex(lines, "1:10"))
    }

    @Test
    fun `a flag exactly matching a line's timestamp lands on that line`() {
        val lines = listOf(line("0:05"), line("0:30"), line("1:00"))

        assertEquals(1, flagLineIndex(lines, "0:30"))
    }

    @Test
    fun `a flag before any transcript line falls back to the first line`() {
        val lines = listOf(line("0:30"), line("1:00"))

        assertEquals(0, flagLineIndex(lines, "0:05"))
    }

    @Test
    fun `a flag after the last line lands on the last line`() {
        val lines = listOf(line("0:05"), line("0:30"))

        assertEquals(1, flagLineIndex(lines, "5:00"))
    }

    @Test
    fun `an empty transcript has nowhere for a flag to land`() {
        assertNull(flagLineIndex(emptyList(), "0:05"))
    }

    @Test
    fun `an unparseable flag label matches nothing`() {
        val lines = listOf(line("0:05"), line("0:30"))

        assertNull(flagLineIndex(lines, "not-a-timestamp"))
    }
}
