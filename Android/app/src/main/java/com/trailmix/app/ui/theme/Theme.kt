package com.trailmix.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trailmix.app.data.settings.ThemeMode

/**
 * Design tokens from the UX overhaul (Oct 2026): one quiet ink, two provenance colors.
 *
 * Actions, selection and links are **ink** ([text]). Amber and teal each mean exactly one thing:
 * amber = typed by you, teal = said in the recording. Red = recording or destroying. Violet =
 * flagged. Every text pair is AA (4.5:1) in both modes. The accents live here rather than in
 * Material roles so a stock component can never pick one up by accident.
 */
@Immutable
data class TrailMixColors(
    val background: Color,
    /** Ink: body text AND every action, selection and link. */
    val text: Color,
    val dim: Color,
    /** surfaceContainer: cards, chips, search field. */
    val card: Color,
    /** surfaceContainerHigh: tonal buttons, selected segment. */
    val cardHigh: Color,
    /** outlineVariant: dividers only (decorative, not 3:1). */
    val border: Color,
    /** outline: outlined buttons, chips, switch off (3:1 against every surface). */
    val outline: Color,
    /** Provenance only: typed by you. */
    val amber: Color,
    /** Provenance only: said in the recording. */
    val teal: Color,
    /** Recording state and destructive actions. */
    val recordingRed: Color,
    /** Text/icon color on a [recordingRed] fill. */
    val onRecordingRed: Color,
    val errorContainer: Color,
    val amberTint: Color,
    val tealTint: Color,
    /** CAP-24: flagged moment. Violet, distinct from typed/said/recording. */
    val flag: Color,
    val flagTint: Color,
    /** Text inside the pale/dark tint spans. Same as [text]: dark tints carry light text in dark mode. */
    val spanText: Color,
    val isDark: Boolean,
)

val LightTrailMixColors = TrailMixColors(
    background = Color(0xFFFBFAF8),
    text = Color(0xFF1A1917),
    dim = Color(0xFF5E5B56),
    card = Color(0xFFF2F0EC),
    cardHigh = Color(0xFFE9E6E1),
    border = Color(0xFFE3E0DA),
    outline = Color(0xFF8C8780),
    amber = Color(0xFFA35100),
    teal = Color(0xFF00717A),
    recordingRed = Color(0xFFBE2D2D),
    onRecordingRed = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE3E1),
    amberTint = Color(0xFFFCE3CC),
    tealTint = Color(0xFFD3EEF0),
    flag = Color(0xFF6B3FD4),
    flagTint = Color(0xFFE9E1FB),
    spanText = Color(0xFF1A1917),
    isDark = false,
)

val DarkTrailMixColors = TrailMixColors(
    background = Color(0xFF121315),
    text = Color(0xFFE8E6E3),
    dim = Color(0xFFA3A09B),
    card = Color(0xFF1C1D20),
    cardHigh = Color(0xFF26282C),
    border = Color(0xFF2E3034),
    outline = Color(0xFF77746F),
    amber = Color(0xFFF2A35E),
    teal = Color(0xFF56C2C8),
    recordingRed = Color(0xFFFF8A82),
    onRecordingRed = Color(0xFF121315),
    errorContainer = Color(0xFF3A1A19),
    amberTint = Color(0xFF3B2815),
    tealTint = Color(0xFF13353A),
    flag = Color(0xFFB9A3FF),
    flagTint = Color(0xFF2A2145),
    spanText = Color(0xFFE8E6E3),
    isDark = true,
)

val LocalTrailMixColors = staticCompositionLocalOf { LightTrailMixColors }
val LocalTrailMixType = staticCompositionLocalOf { DefaultTrailMixType }

object TrailMix {
    val colors: TrailMixColors
        @Composable @ReadOnlyComposable get() = LocalTrailMixColors.current
    val type: TrailMixType
        @Composable @ReadOnlyComposable get() = LocalTrailMixType.current
    val shapes: Shapes
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes
}

/** Spacing on a 4dp grid. [l] is the screen gutter, everywhere. */
object TmSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Four shapes instead of seven: small = chips/tints/fields, medium = cards/menus, large = dialogs/sheets. */
val TrailMixShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** A6: the full Material 3 mapping, so no stock component falls back to the purple default scheme. */
fun TrailMixColors.toMaterialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = text,
        onPrimary = background,
        primaryContainer = cardHigh,
        onPrimaryContainer = text,
        secondary = text,
        onSecondary = background,
        secondaryContainer = cardHigh,
        onSecondaryContainer = text,
        tertiary = text,
        onTertiary = background,
        tertiaryContainer = cardHigh,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = card,
        onSurfaceVariant = dim,
        surfaceTint = Color.Transparent,
        inverseSurface = text,
        inverseOnSurface = background,
        inversePrimary = background,
        outline = outline,
        outlineVariant = border,
        error = recordingRed,
        onError = onRecordingRed,
        errorContainer = errorContainer,
        onErrorContainer = text,
        scrim = Color.Black,
        surfaceBright = background,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = background,
        surfaceContainer = card,
        surfaceContainerHigh = cardHigh,
        surfaceContainerHighest = if (isDark) Color(0xFF303236) else Color(0xFFE0DDD7),
    )
}

@Composable
fun TrailMixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.darkOverride ?: isSystemInDarkTheme()
    val colors = if (dark) DarkTrailMixColors else LightTrailMixColors
    val type = DefaultTrailMixType

    CompositionLocalProvider(
        LocalTrailMixColors provides colors,
        LocalTrailMixType provides type,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            typography = type.toMaterialTypography(),
            shapes = TrailMixShapes,
        ) {
            // Bare Text() calls outside a Surface otherwise get the platform default, not our body role.
            ProvideTextStyle(type.body, content)
        }
    }
}
