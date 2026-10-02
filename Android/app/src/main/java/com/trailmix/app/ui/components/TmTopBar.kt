package com.trailmix.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * A4: the one top bar. Replaces the five hand-rolled header styles. Slots: a navigation icon,
 * a title with an optional subtitle, and up to two actions plus an overflow. 64dp tall, 16dp
 * gutter (the icon buttons are 48dp, so their glyphs sit on the gutter line). Does not handle
 * window insets; the screen's root does, as before.
 */
@Composable
fun TmTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = if (navigation != null) TmSpacing.xs else TmSpacing.l, end = TmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (navigation != null) TmSpacing.xs else 0.dp, end = TmSpacing.s),
        ) {
            Text(
                text = title,
                style = TrailMix.type.title,
                color = TrailMix.colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TrailMix.type.caption,
                    color = TrailMix.colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 48dp icon button with a vector or drawable glyph, ink by default. */
@Composable
fun TmIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) TrailMix.colors.text else TrailMix.colors.dim,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun TmIconButton(
    drawable: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        TmIcon(
            id = drawable,
            contentDescription = contentDescription,
            tint = if (enabled) TrailMix.colors.text else TrailMix.colors.dim,
        )
    }
}

@Composable
fun TmBackButton(onClick: () -> Unit, contentDescription: String = "Back") {
    TmIconButton(Icons.AutoMirrored.Filled.ArrowBack, contentDescription, onClick)
}

@Composable
fun TmCloseButton(onClick: () -> Unit, contentDescription: String = "Close") {
    TmIconButton(Icons.Filled.Close, contentDescription, onClick)
}

/** One entry in a [TmOverflowMenu]. */
data class TmMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** Shown as a quiet trailing count (for example the number of recently deleted notes). */
    val badge: String? = null,
)

/** The standard "More options" overflow. Everything that is not a primary action lives here. */
@Composable
fun TmOverflowMenu(items: List<TmMenuItem>, contentDescription: String = "More options") {
    var open by remember { mutableStateOf(false) }
    Box {
        TmIconButton(Icons.Filled.MoreVert, contentDescription, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    enabled = item.enabled,
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.label, style = TrailMix.type.label)
                            if (item.badge != null) {
                                Text(
                                    item.badge,
                                    style = TrailMix.type.caption,
                                    color = TrailMix.colors.dim,
                                    modifier = Modifier.padding(start = TmSpacing.l),
                                )
                            }
                        }
                    },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}
