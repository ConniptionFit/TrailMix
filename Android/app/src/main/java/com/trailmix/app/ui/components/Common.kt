package com.trailmix.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.home.ActiveCaptureUi
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.flow.StateFlow

/** Uppercase section label — 11sp/600, .04em tracking, dimmed (design token). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = TrailMix.colors.dim,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.44.sp,
    )
}

/** Top bar used by Transcript / Chat & Recipes: back chevron + 15sp/500 title. */
@Composable
fun BackTitleBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackChevron(onBack)
        Text(
            text = title,
            color = TrailMix.colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * A11Y-01 (2026-09-19): was a bare 20dp `Icon` with `indication = null` — the primary back
 * affordance on Note detail, Transcript, Chat, Cross-note chat, Meetings and now Settings sat
 * well under the 48dp minimum touch target and gave no press feedback at all. The icon glyph
 * stays visually 20dp (unchanged look); [Modifier.minimumInteractiveComponentSize] pads the
 * actual hit target out to 48dp without adding visible padding, and the ripple is restored.
 */
@Composable
fun BackChevron(onBack: () -> Unit) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Back",
        tint = TrailMix.colors.dim,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = onBack,
            ),
    )
}

/**
 * PERF-04 (2026-09-19): the in-progress transcription chip (CAP-10) — shared by Home and Note
 * detail (previously duplicated in both screens), and split out into its own composable so its
 * 1 Hz tick (the live [ActiveCaptureUi.elapsedLabel]) only recomposes this small `Row`, not the
 * whole host screen. [activeCapture] is a [StateFlow] rather than an already-collected value
 * specifically so the `collectAsStateWithLifecycle` call happens *inside* this composable —
 * collecting it one level up in the caller and passing the resulting value down would put the
 * same recomposition scope right back where it started.
 */
@Composable
fun ActiveCaptureChip(activeCapture: StateFlow<ActiveCaptureUi?>, onOpen: () -> Unit) {
    val active by activeCapture.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    active?.let { state ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.amber)
                .clickable(onClick = onOpen)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (if (state.paused) "Paused" else "Recording") +
                    (state.meetingTitle?.let { " · $it" } ?: ""),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = state.elapsedLabel,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
