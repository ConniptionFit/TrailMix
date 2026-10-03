package com.trailmix.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.RecentlyDeleted
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmDestructiveButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmListRow
import com.trailmix.app.ui.components.TmMenuItem
import com.trailmix.app.ui.components.TmOverflowMenu
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmSheetAction
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * R1/R2 (REL-06): soft-deleted notes with the time left before the 1-day purge. Restore is one
 * tap on the row. Tapping the row opens a sheet (Restore, Delete now); Delete now then asks once
 * more in a dialog that names the note, which is the only filled red button in the app.
 */
@Composable
fun RecentlyDeletedScreen(
    onBack: () -> Unit,
    viewModel: RecentlyDeletedViewModel = hiltViewModel(),
) {
    val c = TrailMix.colors
    val notes by viewModel.deletedNotes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val res = context.resources
            val message = when (event.kind) {
                DeletedEvent.Kind.RESTORED -> {
                    res.getString(R.string.deleted_restored)
                }

                DeletedEvent.Kind.ERASED -> {
                    res.getQuantityString(R.plurals.deleted_erased, event.count, event.count)
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    // The note whose Restore / Delete now sheet is open.
    var actingOn by remember { mutableStateOf<NoteEntity?>(null) }
    // UX-34: the one truly irreversible action asks a second time, naming the note.
    var erasing by remember { mutableStateOf<NoteEntity?>(null) }
    var erasingAll by remember { mutableStateOf(false) }

    erasing?.let { note ->
        EraseDialog(
            title = stringResource(R.string.deleted_erase_title, note.title),
            body = stringResource(R.string.deleted_erase_body),
            onConfirm = {
                erasing = null
                viewModel.deleteForever(note.id)
            },
            onDismiss = { erasing = null },
        )
    }
    if (erasingAll) {
        EraseDialog(
            title = pluralStringResource(R.plurals.deleted_erase_all_title, notes.size, notes.size),
            body = stringResource(R.string.deleted_erase_body),
            onConfirm = {
                erasingAll = false
                viewModel.deleteAllForever()
            },
            onDismiss = { erasingAll = false },
        )
    }
    actingOn?.let { note ->
        TmSheet(onDismiss = { actingOn = null }, title = note.title) {
            TmSheetAction(
                label = stringResource(R.string.deleted_restore),
                drawable = TmIcons.Undo,
                onClick = {
                    actingOn = null
                    viewModel.restore(note.id)
                },
            )
            TmSheetAction(
                label = stringResource(R.string.deleted_delete_now),
                drawable = TmIcons.Delete,
                destructive = true,
                onClick = {
                    actingOn = null
                    erasing = note
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TmTopBar(
                title = stringResource(R.string.deleted_title),
                navigation = { TmBackButton(onClick = onBack, contentDescription = stringResource(R.string.action_back)) },
                actions = {
                    if (notes.isNotEmpty()) {
                        TmOverflowMenu(
                            contentDescription = stringResource(R.string.home_more),
                            items = listOf(
                                TmMenuItem(stringResource(R.string.deleted_delete_all), onClick = { erasingAll = true }),
                            ),
                        )
                    }
                },
            )
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                Text(
                    text = stringResource(R.string.deleted_intro),
                    style = TrailMix.type.bodySmall,
                    color = c.dim,
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                )
                if (notes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.deleted_empty),
                        style = TrailMix.type.body,
                        color = c.dim,
                        modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(notes, key = { it.id }) { note ->
                            val timeLeft = RecentlyDeleted.timeLeftLabel(note.deletedAtEpochMs ?: 0L, System.currentTimeMillis())
                            TmListRow(
                                title = note.title,
                                subtitle = timeLeft,
                                onClick = { actingOn = note },
                                trailing = {
                                    TmTextButton(
                                        label = stringResource(R.string.deleted_restore),
                                        onClick = { viewModel.restore(note.id) },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        TmSnackbarHost(state = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** R2: the final step. A filled red button says what is lost and what is not. */
@Composable
private fun EraseDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = TrailMix.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = TrailMix.shapes.large,
        containerColor = c.card,
        title = { Text(title, style = TrailMix.type.title, color = c.text) },
        text = { Text(body, style = TrailMix.type.bodySmall, color = c.dim) },
        confirmButton = { TmDestructiveButton(stringResource(R.string.deleted_erase_confirm), onClick = onConfirm) },
        dismissButton = { TmTextButton(stringResource(R.string.deleted_erase_keep), onClick = onDismiss) },
    )
}
