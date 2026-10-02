package com.trailmix.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * A1: one list row for Settings, Recently deleted and Meetings. 56dp with one line of
 * supporting text, 72dp with two (the row grows with the font scale; nothing is a fixed height).
 */
@Composable
fun TmListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = TrailMix.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = if (subtitle != null) 72.dp else 56.dp)
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(TmSpacing.l))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = title,
                style = TrailMix.type.heading,
                color = if (enabled) c.text else c.dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TrailMix.type.bodySmall,
                    color = c.dim,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(TmSpacing.m))
            trailing()
        }
    }
}

/** Row ending in a disclosure chevron: the Settings top-level groups. */
@Composable
fun TmNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    TmListRow(
        title = title,
        subtitle = subtitle,
        leading = leading,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TrailMix.colors.dim,
            )
        },
    )
}

/**
 * G3: a switch row. The whole row is one toggleable with `Role.Switch`, so TalkBack reads
 * "<title>, switch, on/off" and the touch target is the full row, not a 40dp pill.
 */
@Composable
fun TmSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = TrailMix.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = if (subtitle != null) 72.dp else 56.dp)
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.l),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, style = TrailMix.type.heading, color = if (enabled) c.text else c.dim)
            if (subtitle != null) Text(subtitle, style = TrailMix.type.bodySmall, color = c.dim)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.background,
                checkedTrackColor = c.text,
                checkedBorderColor = c.text,
                uncheckedThumbColor = c.outline,
                uncheckedTrackColor = c.background,
                uncheckedBorderColor = c.outline,
                disabledCheckedTrackColor = c.cardHigh,
                disabledUncheckedTrackColor = c.card,
            ),
        )
    }
}
