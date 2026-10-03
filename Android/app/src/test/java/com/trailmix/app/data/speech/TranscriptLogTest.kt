package com.trailmix.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLogTest {
    @Test fun removeFlagRemovesOnlyTheLastMatchingOne() {
        val log = TranscriptLog()
        log.appendFlag("0:31")
        log.appendFlag("1:02")
        log.appendFlag("0:31")
        assertTrue(log.removeFlag("0:31"))
        assertEquals(listOf("0:31", "1:02"), log.flagSnapshot())
        assertEquals(2, log.flagCount())
    }

    @Test fun removingAFlagThatIsNotThereIsReportedAndHarmless() {
        val log = TranscriptLog()
        log.appendFlag("0:31")
        assertFalse(log.removeFlag("5:00"))
        assertEquals(1, log.flagCount())
    }
}
