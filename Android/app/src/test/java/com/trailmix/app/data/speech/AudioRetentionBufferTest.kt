package com.trailmix.app.data.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRetentionBufferTest {

    @Test
    fun `appended chunks concatenate in order`() {
        val buffer = AudioRetentionBuffer()
        buffer.append(shortArrayOf(1, 2, 3), 3)
        buffer.append(shortArrayOf(4, 5), 2)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), buffer.toShortArray())
        assertEquals(5, buffer.sampleCount)
    }

    @Test
    fun `only the first count samples of a chunk are retained`() {
        val buffer = AudioRetentionBuffer()
        buffer.append(shortArrayOf(1, 2, 3, 99, 99), 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), buffer.toShortArray())
    }

    @Test
    fun `a zero or negative count is ignored`() {
        val buffer = AudioRetentionBuffer()
        buffer.append(shortArrayOf(1, 2, 3), 0)
        assertEquals(0, buffer.sampleCount)
    }

    @Test
    fun `appending stops once maxSamples is reached, silently dropping the rest`() {
        val buffer = AudioRetentionBuffer(maxSamples = 4)
        buffer.append(shortArrayOf(1, 2, 3), 3)
        buffer.append(shortArrayOf(4, 5, 6), 3) // only 1 more sample fits
        buffer.append(shortArrayOf(7, 8), 2) // buffer is already full, dropped entirely
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), buffer.toShortArray())
        assertEquals(4, buffer.sampleCount)
    }

    @Test
    fun `an empty buffer produces an empty array`() {
        assertArrayEquals(ShortArray(0), AudioRetentionBuffer().toShortArray())
    }
}
