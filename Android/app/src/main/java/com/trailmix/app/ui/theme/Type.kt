package com.trailmix.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.trailmix.app.R

/**
 * A3 (UX overhaul): 17 ad-hoc sizes became 5 sizes in 7 roles. Instrument Sans (variable,
 * weights 400/500/600) and IBM Plex Mono are bundled in res/font, both SIL OFL, because the
 * app has no network permission and so can never use downloadable fonts.
 */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.instrument_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.instrument_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * The seven roles. Line heights are fixed per role so nothing sets its own; nothing is smaller
 * than 12sp. [mono] is for timestamps and timers and uses tabular figures.
 */
@Immutable
data class TrailMixType(
    /** 26/32 600. Note title, Home wordmark, merge state headline. */
    val display: TextStyle,
    /** 20/28 600. Every top bar title, dialog and sheet title. */
    val title: TextStyle,
    /** 16/24 600. Note sections, list-row titles, day headers. */
    val heading: TextStyle,
    /** 16/24 400. Note bullets, transcript lines, chat, typed notes. */
    val body: TextStyle,
    /** 14/20 400. Supporting text, dialog body, list-row subtitles. */
    val bodySmall: TextStyle,
    /** 14/20 500. Buttons, chips, tabs, menu items. */
    val label: TextStyle,
    /** 12/16 500. Meta lines. */
    val caption: TextStyle,
    /** 12/16 600, +0.06em, for ALL-CAPS section overlines. */
    val overline: TextStyle,
    /** 12/16 Plex Mono 500. Timestamps and timers. */
    val mono: TextStyle,
)

val DefaultTrailMixType = TrailMixType(
    display = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em),
    title = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    heading = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    overline = TextStyle(fontFamily = InstrumentSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
    mono = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
)

/** Maps the roles onto Material 3 slots so stock components (dialogs, buttons, chips) pick them up. */
fun TrailMixType.toMaterialTypography(): Typography = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = display,
    headlineLarge = display,
    headlineMedium = display,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = heading,
    titleSmall = label,
    bodyLarge = body,
    bodyMedium = bodySmall,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = caption,
    labelSmall = caption,
)
