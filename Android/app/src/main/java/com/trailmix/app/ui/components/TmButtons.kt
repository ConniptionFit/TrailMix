package com.trailmix.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TrailMix

/**
 * A1/A11: the button hierarchy. 48dp tall, fully rounded. One primary per screen; a primary is
 * ink, never amber and never recording red. Destructive is for the final step only.
 */
private val ButtonHeight = 48.dp

/** Leading glyph for a button: either a vector, a drawable id, or none. */
sealed interface TmButtonIcon {
    data class Vector(val image: ImageVector) : TmButtonIcon

    data class Drawable(val id: Int) : TmButtonIcon
}

@Composable
private fun ButtonContent(label: String, icon: TmButtonIcon?) {
    when (icon) {
        is TmButtonIcon.Vector -> Icon(icon.image, contentDescription = null, modifier = Modifier.size(18.dp))
        is TmButtonIcon.Drawable -> TmIcon(icon.id, contentDescription = null, size = 18.dp, tint = LocalContentColor.current)
        null -> Unit
    }
    if (icon != null) Spacer(Modifier.width(8.dp))
    Text(label, style = TrailMix.type.label)
}

@Composable
fun TmButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: TmButtonIcon? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TrailMix.colors.text,
            contentColor = TrailMix.colors.background,
            disabledContainerColor = TrailMix.colors.cardHigh,
            disabledContentColor = TrailMix.colors.dim,
        ),
    ) { ButtonContent(label, icon) }
}

@Composable
fun TmTonalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: TmButtonIcon? = null,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = TrailMix.colors.cardHigh,
            contentColor = TrailMix.colors.text,
            disabledContainerColor = TrailMix.colors.card,
            disabledContentColor = TrailMix.colors.dim,
        ),
    ) { ButtonContent(label, icon) }
}

@Composable
fun TmOutlinedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: TmButtonIcon? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, TrailMix.colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TrailMix.colors.text,
            disabledContentColor = TrailMix.colors.dim,
        ),
    ) { ButtonContent(label, icon) }
}

@Composable
fun TmTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) TrailMix.colors.recordingRed else TrailMix.colors.text,
            disabledContentColor = TrailMix.colors.dim,
        ),
    ) { Text(label, style = TrailMix.type.label) }
}

/** The only filled red button in the app: the last step of an irreversible action. */
@Composable
fun TmDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = ButtonHeight),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = TrailMix.colors.recordingRed,
            contentColor = TrailMix.colors.onRecordingRed,
            disabledContainerColor = TrailMix.colors.cardHigh,
            disabledContentColor = TrailMix.colors.dim,
        ),
    ) { Text(label, style = TrailMix.type.label) }
}
