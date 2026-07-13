package com.trailmix.app.data.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {

    @Test
    fun `stereo downmix averages channel pairs`() {
        val stereo = shortArrayOf(100, 300, -50, -150, 0, 1)
        assertArrayEquals(shortArrayOf(200, -100, 0), Pcm.downmixStereoToMono(stereo, 6))
    }

    @Test
    fun `decimate3 averages triples for 48k to 16k`() {
        val input = shortArrayOf(3, 6, 9, 30, 60, 90)
        assertArrayEquals(shortArrayOf(6, 60), Pcm.decimate3(input, 6))
    }

    @Test
    fun `mixInto saturates instead of wrapping`() {
        val dst = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 100)
        Pcm.mixInto(dst, shortArrayOf(1000, -1000, 23), 3)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 123), dst)
    }

    @Test
    fun `little endian serialization is byte exact`() {
        val bytes = Pcm.toLittleEndianBytes(shortArrayOf(0x1234, -2), 2)
        assertArrayEquals(
            byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte()),
            bytes,
        )
    }

    @Test
    fun `ring buffer round-trips and reports size`() {
        val ring = ShortRingBuffer(8)
        ring.push(shortArrayOf(1, 2, 3, 4, 5), 5)
        assertEquals(5, ring.availableToRead())
        val out = ShortArray(3)
        assertEquals(3, ring.pop(out, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), out)
        // Wraps around the physical end.
        ring.push(shortArrayOf(6, 7, 8, 9), 4)
        val rest = ShortArray(6)
        assertEquals(6, ring.pop(rest, 6))
        assertArrayEquals(shortArrayOf(4, 5, 6, 7, 8, 9), rest)
    }

    @Test
    fun `ring buffer drops chunk when full`() {
        val ring = ShortRingBuffer(4)
        assertEquals(true, ring.push(shortArrayOf(1, 2, 3), 3))
        assertEquals(false, ring.push(shortArrayOf(4, 5), 2))
        assertEquals(3, ring.availableToRead())
    }
}
