package com.trailmix.app.ui.components

import kotlin.math.abs
import kotlin.math.sin

/**
 * Pure math behind [TmRecordingIndicator]'s bars, kept out of Compose so it can be unit tested.
 * The meter is the one continuous motion in the app (A10), so it must be cheap and bounded.
 */
object LevelMeter {
    const val BARS = 5

    /** Each bar reacts a little differently so the five don't move in lockstep. */
    private val weights = floatArrayOf(0.8f, 0.55f, 1.0f, 0.7f, 0.9f)

    /** Minimum fraction of full height so a silent meter still reads as five bars, not nothing. */
    const val FLOOR = 0.18f

    /**
     * Heights in 0..1 for each bar given [level] (0..1 from the mic RMS) and a slowly advancing
     * [phase] (seconds) that keeps the bars from freezing between chunks. Always within
     * [FLOOR]..1 and monotone in [level] for a fixed phase.
     */
    fun heights(level: Float, phase: Float): FloatArray {
        val l = level.coerceIn(0f, 1f)
        return FloatArray(BARS) { i ->
            val wobble = 0.85f + 0.15f * abs(sin(phase * (1.3f + i * 0.37f) + i))
            (FLOOR + (1f - FLOOR) * l * weights[i] * wobble).coerceIn(FLOOR, 1f)
        }
    }

    /** Reduce-motion form: one flat bar level in five steps, no per-bar wobble. */
    fun steppedLevel(level: Float): Int = (level.coerceIn(0f, 1f) * BARS).toInt().coerceIn(0, BARS)
}
