package com.trailmix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Design tokens from the TrailMix Android handoff (oklch converted to sRGB).
 * Amber = primary actions + fragment-sourced content; teal = transcript-sourced.
 */
@Immutable
data class TrailMixColors(
    val background: Color,
    val text: Color,
    val dim: Color,
    val card: Color,
    val border: Color,
    val amber: Color,
    val teal: Color,
    val recordingRed: Color,
    val amberTint: Color,
    val tealTint: Color,
    /** CAP-24: flagged-moment marker/highlight — violet, distinct from amber/teal/recordingRed
     * so a flag never reads as "typed", "spoken", or "recording". Not from the original design
     * handoff (flags are a new feature); follows the same paired-tint convention regardless. */
    val flag: Color,
    val flagTint: Color,
    /** Fixed dark text used inside pale tint spans in both modes. */
    val spanText: Color,
    val isDark: Boolean,
)

val LightTrailMixColors = TrailMixColors(
    background = Color(0xFFFFFFFF),
    text = Color(0xFF171614),        // oklch(20% 0.005 80)
    dim = Color(0xFF656360),         // oklch(50% 0.005 80)
    card = Color(0xFFF6F5F2),        // oklch(97% 0.004 80)
    border = Color(0xFFDFDEDB),      // oklch(90% 0.004 80)
    amber = Color(0xFFBB5D00),       // oklch(58% 0.15 55)
    teal = Color(0xFF00848B),        // oklch(55% 0.11 200)
    recordingRed = Color(0xFFC53637), // oklch(55% 0.18 25)
    amberTint = Color(0xFFFFE0C9),   // oklch(93% 0.05 55)
    tealTint = Color(0xFFCEEFF1),    // oklch(93% 0.035 200)
    flag = Color(0xFF7C3AED),
    flagTint = Color(0xFFE9DFFC),
    spanText = Color(0xFF1D1A15),    // oklch(22% 0.01 80)
    isDark = false,
)

val DarkTrailMixColors = LightTrailMixColors.copy(
    background = Color(0xFF101214), // oklch(18% 0.006 260)
    text = Color(0xFFE6E4E2),       // oklch(92% 0.004 80)
    dim = Color(0xFF84868A),        // oklch(62% 0.006 260)
    card = Color(0xFF1D1F23),       // oklch(24% 0.008 260)
    border = Color(0xFF2B2E33),     // oklch(30% 0.01 260)
    isDark = true,
)

val LocalTrailMixColors = staticCompositionLocalOf { LightTrailMixColors }

object TrailMix {
    val colors: TrailMixColors
        @Composable get() = LocalTrailMixColors.current
}

@Composable
fun TrailMixTheme(
    darkModeOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkModeOverride ?: isSystemInDarkTheme()
    val colors = if (dark) DarkTrailMixColors else LightTrailMixColors

    val materialScheme = if (dark) {
        darkColorScheme(
            primary = colors.amber,
            background = colors.background,
            surface = colors.background,
            onBackground = colors.text,
            onSurface = colors.text,
        )
    } else {
        lightColorScheme(
            primary = colors.amber,
            background = colors.background,
            surface = colors.background,
            onBackground = colors.text,
            onSurface = colors.text,
        )
    }

    CompositionLocalProvider(LocalTrailMixColors provides colors) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}
