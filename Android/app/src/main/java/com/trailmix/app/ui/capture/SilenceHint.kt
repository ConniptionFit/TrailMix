package com.trailmix.app.ui.capture

/** C1: when to say "Not hearing anything yet". Pure so the threshold is pinned by a test. */
object SilenceHint {
    /** Below this the input level counts as silence. */
    const val AUDIBLE_LEVEL = 0.04f

    /** How long without anything audible before the hint appears (the design's 10 s). */
    const val AFTER_MS = 10_000L

    fun shouldShow(nowMs: Long, lastHeardMs: Long, recording: Boolean, levelAvailable: Boolean): Boolean =
        recording && levelAvailable && nowMs - lastHeardMs >= AFTER_MS
}
