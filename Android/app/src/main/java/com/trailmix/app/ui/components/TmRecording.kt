package com.trailmix.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.delay

enum class RecordingState { RECORDING, PAUSED, UNAVAILABLE }

@Composable
private fun reduceMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

/**
 * C1/A10: the live recording indicator. A red dot-less pill: five level bars (red while
 * recording, flat grey when paused) plus a tabular timer. [level] is the mic RMS/peak in 0..1
 * from `CaptureUiState.level`; with reduce-motion on the bars become a static stepped meter.
 * The same component shows on Capture, in keyboard mode, in the transcript sheet and on Home.
 */
@Composable
fun TmRecordingIndicator(
    state: RecordingState,
    elapsedLabel: String,
    level: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    stateLabel: String? = null,
) {
    val c = TrailMix.colors
    val barColor = when (state) {
        RecordingState.RECORDING -> c.recordingRed
        else -> c.outline
    }
    val description = when (state) {
        RecordingState.RECORDING -> "Recording, $elapsedLabel"
        RecordingState.PAUSED -> "Paused, $elapsedLabel"
        RecordingState.UNAVAILABLE -> "Microphone unavailable"
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(c.card)
            .padding(horizontal = TmSpacing.m, vertical = if (compact) 4.dp else TmSpacing.s)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        LevelBars(
            level = if (state == RecordingState.RECORDING) level else 0f,
            color = barColor,
            height = if (compact) 14.dp else 18.dp,
            animate = state == RecordingState.RECORDING,
        )
        Text(
            text = stateLabel?.let { "$it · $elapsedLabel" } ?: elapsedLabel,
            style = TrailMix.type.mono,
            color = c.text,
        )
    }
}

@Composable
private fun LevelBars(level: Float, color: Color, height: Dp, animate: Boolean) {
    val still = reduceMotion()
    val smoothed by animateFloatAsState(targetValue = level, label = "level")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate, still) {
        if (animate && !still) {
            while (true) {
                delay(100)
                phase += 0.1f
            }
        }
    }
    val heights = if (still) {
        val steps = LevelMeter.steppedLevel(smoothed)
        FloatArray(LevelMeter.BARS) { i -> if (i < steps) 1f else LevelMeter.FLOOR }
    } else {
        LevelMeter.heights(smoothed, phase)
    }
    Row(
        modifier = Modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height * h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/**
 * C7/D7: one progress card for every long task (merge, rebuild, recovery rebuild). Determinate
 * when the pipeline knows N of M, indeterminate otherwise. [error] swaps in the failure state
 * with Retry, because a failed merge must say the transcript and notes are safe (REL-24).
 */
@Composable
fun TmLongTaskProgress(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    progress: Float? = null,
    counter: String? = null,
    reassurance: String? = "Safe to leave. It keeps going in the background and notifies you when it's ready.",
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    val c = TrailMix.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(if (error != null) c.errorContainer else c.card)
            .padding(TmSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = TrailMix.type.heading, color = c.text, modifier = Modifier.weight(1f, fill = false))
            if (counter != null && error == null) Text(counter, style = TrailMix.type.mono, color = c.dim)
        }
        if (error != null) {
            Text(error, style = TrailMix.type.bodySmall, color = c.text)
            Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.s)) {
                if (onRetry != null) TmTonalButton("Try again", onClick = onRetry)
                if (onCancel != null) TmTextButton("Dismiss", onClick = onCancel)
            }
        } else {
            if (detail != null) Text(detail, style = TrailMix.type.bodySmall, color = c.dim)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.text,
                    trackColor = c.cardHigh,
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.text, trackColor = c.cardHigh)
            }
            if (reassurance != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(TmSpacing.s))
                    Text(reassurance, style = TrailMix.type.caption, color = c.dim)
                }
            }
            if (onCancel != null) TmTextButton("Cancel", onClick = onCancel)
        }
    }
}

/** A finished step line inside a long task ("Transcript finished · 318 lines"). */
@Composable
fun TmStepLine(text: String, done: Boolean, modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = c.text, modifier = Modifier.size(18.dp))
        } else {
            Box(Modifier.size(18.dp))
        }
        Text(text, style = TrailMix.type.bodySmall, color = if (done) c.dim else c.text)
    }
}
