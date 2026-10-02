package com.trailmix.app.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * AI-28: retry a transient failure with a growing pause.
 *
 * AICore answers `GenAiException.ErrorCode.BUSY` (9) when it is still tearing down the previous
 * inference session: seen on the Pixel 9 Pro as three back-to-back structuring calls rejected within
 * 43 ms of each other right after a successful one, so a multi-part merge lost every part after the
 * first. BUSY means "try again shortly", so every model call waits and retries a few times before
 * giving up (and the existing fail-soft fallbacks take over).
 *
 * Pure (the predicate decides what is transient), so the retry policy has unit tests without ML Kit.
 */
suspend fun <T> retryWhile(
    maxRetries: Int,
    backoffMs: Long,
    shouldRetry: (Throwable) -> Boolean,
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e // cancellation is never a failure to retry
        } catch (e: Throwable) {
            if (attempt >= maxRetries || !shouldRetry(e)) throw e
            attempt++
            delay(backoffMs * attempt)
        }
    }
}
