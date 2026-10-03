package com.trailmix.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Pins the contrast claims on the design-system page so a token edit can't quietly break AA. */
class TrailMixColorsTest {
    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun lum(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
    }

    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = lum(a).coerceAtLeast(lum(b)) to lum(a).coerceAtMost(lum(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, c: TrailMixColors) {
        val surfaces = listOf(c.background, c.card, c.cardHigh)
        for (s in surfaces) {
            for ((label, fg) in listOf("text" to c.text, "dim" to c.dim, "amber" to c.amber, "teal" to c.teal, "red" to c.recordingRed, "flag" to c.flag)) {
                assertTrue("$name $label on $s = ${ratio(fg, s)}", ratio(fg, s) >= 4.5)
            }
        }
        // Outlines are required to reach 3:1 against the page and card surfaces they sit on; the
        // design does not put them on surfaceContainerHigh (that is the tonal-button fill).
        for (s in listOf(c.background, c.card)) {
            assertTrue("$name outline on $s = ${ratio(c.outline, s)}", ratio(c.outline, s) >= 3.0)
        }
        assertTrue("$name text on amberTint", ratio(c.spanText, c.amberTint) >= 4.5)
        assertTrue("$name text on tealTint", ratio(c.spanText, c.tealTint) >= 4.5)
        assertTrue("$name text on flagTint", ratio(c.spanText, c.flagTint) >= 4.5)
        assertTrue("$name onRecordingRed on red", ratio(c.onRecordingRed, c.recordingRed) >= 4.5)
        assertTrue("$name text on errorContainer", ratio(c.text, c.errorContainer) >= 4.5)
    }

    @Test fun lightPairsAreAA() = check("light", LightTrailMixColors)

    @Test fun darkPairsAreAA() = check("dark", DarkTrailMixColors)
}
