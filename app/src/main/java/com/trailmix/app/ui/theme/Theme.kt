package com.trailmix.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val TrailMixBackground = Color(0xFF121212)
val TrailMixSurface = Color(0xFF1C1C1C)
val TrailMixCard = Color(0xFF242424)
val TrailMixOnBackground = Color(0xFFFFFFFF)
val TrailMixSecondaryText = Color(0xFF9E9E9E)
val TrailMixAccent = Color(0xFFC6D96A)
val TrailMixPrimaryButton = Color(0xFFFFFFFF)
val TrailMixOnPrimaryButton = Color(0xFF121212)

private val DarkColors = darkColorScheme(
    primary = TrailMixPrimaryButton,
    onPrimary = TrailMixOnPrimaryButton,
    secondary = TrailMixAccent,
    onSecondary = TrailMixOnPrimaryButton,
    background = TrailMixBackground,
    onBackground = TrailMixOnBackground,
    surface = TrailMixSurface,
    onSurface = TrailMixOnBackground,
    surfaceVariant = TrailMixCard,
    onSurfaceVariant = TrailMixSecondaryText,
    outline = Color(0xFF3A3A3A),
)

val TrailMixTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 44.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun TrailMixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = TrailMixTypography,
        content = content,
    )
}
