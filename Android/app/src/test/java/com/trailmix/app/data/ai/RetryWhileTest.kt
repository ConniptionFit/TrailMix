package com.trailmix.app.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** AI-28: the BUSY retry policy, independent of ML Kit. */
class RetryWhileTest {

    private class Busy : Exception("busy")

    private class Other : Exception("other")

    private fun isBusy(e: Throwable) = e is Busy

    @Test
    fun `a transient failure is retried until it succeeds`() = runBlocking {
        var calls = 0
        val result = retryWhile(maxRetries = 3, backoffMs = 1, shouldRetry = ::isBusy) {
            calls++
            if (calls < 3) throw Busy()
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `it gives up after maxRetries and rethrows the last failure`() = runBlocking {
        var calls = 0
        val failure = Busy()
        try {
            retryWhile(maxRetries = 2, backoffMs = 1, shouldRetry = ::isBusy) {
                calls++
                throw failure
            }
            fail("expected the failure to propagate")
        } catch (e: Busy) {
            assertSame(failure, e)
        }

        assertEquals(3, calls) // the first try plus two retries
    }

    @Test
    fun `a failure that is not transient is not retried`() = runBlocking {
        var calls = 0
        try {
            retryWhile(maxRetries = 5, backoffMs = 1, shouldRetry = ::isBusy) {
                calls++
                throw Other()
            }
            fail("expected the failure to propagate")
        } catch (e: Other) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `cancellation is never retried even if the predicate would accept it`() = runBlocking {
        var calls = 0
        try {
            retryWhile(maxRetries = 5, backoffMs = 1, shouldRetry = { true }) {
                calls++
                throw CancellationException("cancelled")
            }
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals(1, calls)
        }
    }

    @Test
    fun `the pause grows with each retry`() = runBlocking {
        val start = System.nanoTime()
        var calls = 0
        retryWhile(maxRetries = 2, backoffMs = 20, shouldRetry = ::isBusy) {
            calls++
            if (calls <= 2) throw Busy()
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("expected at least 20ms + 40ms of backoff, got ${elapsedMs}ms", elapsedMs >= 55)
    }
}
