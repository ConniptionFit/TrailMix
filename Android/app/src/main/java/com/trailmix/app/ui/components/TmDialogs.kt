package com.trailmix.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A1/D5: one confirm dialog for all ~20 call sites. The title names the action ("Discard this
 * recording?"); both buttons are verbs. [destructive] colors the confirm label red; the dialog
 * is never a filled red button (that is reserved for [TmDestructiveButton] in a final step).
 */
@Composable
fun TmConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = TrailMix.shapes.large,
        containerColor = TrailMix.colors.card,
        title = { Text(title, style = TrailMix.type.title, color = TrailMix.colors.text) },
        text = { Text(body, style = TrailMix.type.bodySmall, color = TrailMix.colors.dim) },
        confirmButton = { TmTextButton(confirmLabel, onClick = onConfirm, destructive = destructive) },
        dismissButton = { TmTextButton(dismissLabel, onClick = onDismiss) },
    )
}

/** Bottom sheet with 20dp top corners. Content is a plain column; use [TmSheetAction] rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = TrailMix.colors.background,
        contentColor = TrailMix.colors.text,
        scrimColor = Color.Black.copy(alpha = 0.32f),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = TmSpacing.l)) {
            if (title != null) {
                Text(
                    text = title,
                    style = TrailMix.type.title,
                    color = TrailMix.colors.text,
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
                )
            }
            content()
        }
    }
}

/** A tappable 56dp row inside a [TmSheet]: icon, label, optional destructive color. */
@Composable
fun TmSheetAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    drawable: Int? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = when {
        !enabled -> TrailMix.colors.dim
        destructive -> TrailMix.colors.recordingRed
        else -> TrailMix.colors.text
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = TmSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.l),
    ) {
        if (drawable != null) TmIcon(drawable, null, tint = tint)
        Text(label, style = TrailMix.type.body, color = tint)
    }
}

/** How long a delete's Undo stays up. Deletes are soft, so 8s is generous but never modal. */
const val UNDO_WINDOW_MS = 8_000L

/**
 * B3: show [message] with an Undo action for [UNDO_WINDOW_MS] and report whether the user
 * tapped it. Material's own durations top out at 10s Long with no way to pick 8s, so this uses
 * Indefinite and dismisses itself on a timeout.
 */
suspend fun SnackbarHostState.showUndo(message: String, actionLabel: String = "Undo"): Boolean {
    var undone = false
    withTimeoutOrNull(UNDO_WINDOW_MS) {
        val result = showSnackbar(message = message, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
        undone = result == SnackbarResult.ActionPerformed
    }
    currentSnackbarData?.dismiss()
    return undone
}

/** Snackbar host themed on inverseSurface; the action is underlined ink-on-ink (no accent needed). */
@Composable
fun TmSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = state, modifier = modifier) { data ->
        Snackbar(
            modifier = Modifier.padding(TmSpacing.l),
            shape = TrailMix.shapes.medium,
            containerColor = TrailMix.colors.text,
            contentColor = TrailMix.colors.background,
            action = data.visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = { data.performAction() }) {
                        Text(
                            text = label,
                            style = TrailMix.type.label,
                            color = TrailMix.colors.background,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
            },
        ) { Text(data.visuals.message, style = TrailMix.type.bodySmall) }
    }
}
