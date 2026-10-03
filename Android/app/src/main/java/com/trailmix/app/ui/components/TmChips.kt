package com.trailmix.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TrailMix

/**
 * Chips and segmented controls. Selection is ink plus a check glyph, never amber, so it can't
 * be mistaken for provenance. The visual height is 32dp; M3 pads the touch target to 48dp.
 */
@Composable
fun TmFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDrawable: Int? = null,
    leadingVector: ImageVector? = null,
) {
    val c = TrailMix.colors
    val leading: (@Composable () -> Unit)? =
        if (selected || leadingDrawable != null || leadingVector != null) {
            {
                when {
                    selected -> Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    leadingDrawable != null -> TmIcon(leadingDrawable, null, size = 18.dp, tint = LocalContentColor.current)
                    leadingVector != null -> Icon(leadingVector, contentDescription = null, modifier = Modifier.size(18.dp))
                    else -> Unit
                }
            }
        } else {
            null
        }
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, style = TrailMix.type.label) },
        leadingIcon = leading,
        shape = TrailMix.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = c.text,
            iconColor = c.text,
            selectedContainerColor = c.cardHigh,
            selectedLabelColor = c.text,
            selectedLeadingIconColor = c.text,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = c.outline,
            selectedBorderColor = c.cardHigh,
        ),
    )
}

@Composable
fun TmAssistChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDrawable: Int? = null,
    enabled: Boolean = true,
) {
    val c = TrailMix.colors
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label, style = TrailMix.type.label) },
        leadingIcon = leadingDrawable?.let { id ->
            { TmIcon(id, null, size = 18.dp, tint = LocalContentColor.current) }
        },
        shape = TrailMix.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
            labelColor = c.text,
            leadingIconContentColor = c.text,
            disabledLabelColor = c.dim,
        ),
        border = BorderStroke(1.dp, c.outline),
    )
}

/** Single-choice segmented row: Note / My notes, System / Light / Dark. */
@Composable
fun <T> TmSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.cardHigh,
                    activeContentColor = c.text,
                    activeBorderColor = c.outline,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = c.text,
                    inactiveBorderColor = c.outline,
                ),
                label = { Text(label, style = TrailMix.type.label) },
            )
        }
    }
}
