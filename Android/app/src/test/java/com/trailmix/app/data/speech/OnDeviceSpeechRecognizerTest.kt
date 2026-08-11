package com.trailmix.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAP-16: the restart backoff. This is the only part of [OnDeviceSpeechRecognizer] a JVM
 * test can reach — the rest is [android.speech.SpeechRecognizer] glue, which the android.jar
 * stubs cannot run and which CAP-17 fixed by moving to the main looper.
 */
class OnDeviceSpeechRecognizerTest {

    @Test
    fun `the first failure retries quickly`() {
        assertEquals(250L, OnDeviceSpeechRecognizer.retryDelayMs(1))
    }

    @Test
    fun `repeated failures back off, doubling`() {
        assertEquals(250L, OnDeviceSpeechRecognizer.retryDelayMs(1))
        assertEquals(500L, OnDeviceSpeechRecognizer.retryDelayMs(2))
        assertEquals(1_000L, OnDeviceSpeechRecognizer.retryDelayMs(3))
        assertEquals(2_000L, OnDeviceSpeechRecognizer.retryDelayMs(4))
        assertEquals(4_000L, OnDeviceSpeechRecognizer.retryDelayMs(5))
        assertEquals(8_000L, OnDeviceSpeechRecognizer.retryDelayMs(6))
    }

    @Test
    fun `the backoff is capped, however long the recognizer stays broken`() {
        // A capture left running for an hour against a recognizer another app is holding
        // must not schedule its next retry days out — nor overflow into the past.
        listOf(7, 20, 64, 1_000, Int.MAX_VALUE).forEach { attempt ->
            assertEquals("attempt $attempt", 8_000L, OnDeviceSpeechRecognizer.retryDelayMs(attempt))
        }
    }

    @Test
    fun `the delay is never zero or negative`() {
        // A zero delay is the tight restart loop CAP-16 exists to stop.
        (1..200).forEach { attempt ->
            assertTrue("attempt $attempt", OnDeviceSpeechRecognizer.retryDelayMs(attempt) >= 250L)
        }
    }
}
