package com.trailmix.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {
    @Test fun silentMeterStillShowsFiveBarsAtTheFloor() {
        val h = LevelMeter.heights(0f, 1.7f)
        assertEquals(LevelMeter.BARS, h.size)
        h.forEach { assertEquals(LevelMeter.FLOOR, it, 1e-6f) }
    }

    @Test fun heightsStayWithinBoundsForAnyInput() {
        for (level in listOf(-1f, 0f, 0.3f, 1f, 7f)) {
            for (phase in listOf(0f, 0.5f, 12.3f, 1000f)) {
                LevelMeter.heights(level, phase).forEach { assertTrue(it in LevelMeter.FLOOR..1f) }
            }
        }
    }

    @Test fun louderNeverShrinksABar() {
        val quiet = LevelMeter.heights(0.2f, 3f)
        val loud = LevelMeter.heights(0.8f, 3f)
        for (i in quiet.indices) assertTrue(loud[i] >= quiet[i])
    }

    @Test fun reduceMotionStepsAreClampedToFive() {
        assertEquals(0, LevelMeter.steppedLevel(0f))
        assertEquals(2, LevelMeter.steppedLevel(0.45f))
        assertEquals(5, LevelMeter.steppedLevel(1f))
        assertEquals(5, LevelMeter.steppedLevel(9f))
        assertEquals(0, LevelMeter.steppedLevel(-3f))
    }
}
