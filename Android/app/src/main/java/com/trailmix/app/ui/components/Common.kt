package com.trailmix.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trailmix.app.ui.theme.TrailMix

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
