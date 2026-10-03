package com.trailmix.app.ui.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceHintTest {
    @Test fun showsOnlyAfterTenSecondsOfSilence() {
        assertFalse(SilenceHint.shouldShow(9_999, 0, recording = true, levelAvailable = true))
        assertTrue(SilenceHint.shouldShow(10_000, 0, recording = true, levelAvailable = true))
    }

    @Test fun neverShowsWhenPausedOrWithoutALevelSource() {
        assertFalse(SilenceHint.shouldShow(60_000, 0, recording = false, levelAvailable = true))
        // The system-recognizer lane has no AudioRecord of ours, so silence can't be judged.
        assertFalse(SilenceHint.shouldShow(60_000, 0, recording = true, levelAvailable = false))
    }
}
