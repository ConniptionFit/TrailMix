package com.trailmix.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.RecentlyDeleted
import com.trailmix.app.ui.theme.TrailMix

/**
 * REL-06: Recently deleted — soft-deleted notes with the time left before the 1-day purge,
 * each restorable or immediately (unrecoverably) removable. Tapping a row asks which.
 */
@Composable
fun RecentlyDeletedScreen(
    onBack: () -> Unit,
    viewModel: RecentlyDeletedViewModel = hiltViewModel(),
) {
    val c = TrailMix.colors
    val notes by viewModel.deletedNotes.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    // The note whose Restore / Delete now dialog is open.
    var actingOn by remember { mutableStateOf<NoteEntity?>(null) }
    actingOn?.let { note ->
        AlertDialog(
            onDismissRequest = { actingOn = null },
            containerColor = c.card,
            title = { Text(note.title, color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    text = "Restore this note, or delete it now? Deleting now can't be " +
                        "undone — otherwise it stays recoverable here until it's " +
                        "removed automatically (${
                            RecentlyDeleted.timeLeftLabel(
                                note.deletedAtEpochMs ?: 0L,
                                System.currentTimeMillis(),
                            ).replaceFirstChar { it.lowercase() }
                        }).",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Restore",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            actingOn = null
                            viewModel.restore(note.id)
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Row {
                    Text(
                        text = "Delete now",
                        color = c.recordingRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable {
                                actingOn = null
                                viewModel.deleteForever(note.id)
                            }
                            .padding(8.dp),
                    )
                    Text(
                        text = "Cancel",
                        color = c.dim,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { actingOn = null }
                            .padding(8.dp),
                    )
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = c.text,
                    )
                }
                Text(
                    text = "Recently deleted",
                    color = c.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "Deleted notes stay here for 1 day, then they're removed for good. " +
                    "Tap a note to restore it or delete it now.",
                color = c.dim,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            if (notes.isEmpty()) {
                Text(
                    text = "Nothing here — deleted notes appear for 1 day before " +
                        "being removed for good.",
                    color = c.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(notes, key = { it.id }) { note ->
                        DeletedNoteRow(note = note, onClick = { actingOn = note })
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

@Composable
private fun DeletedNoteRow(note: NoteEntity, onClick: () -> Unit) {
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.title,
                    color = c.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = RecentlyDeleted.timeLeftLabel(
                        note.deletedAtEpochMs ?: 0L,
                        System.currentTimeMillis(),
                    ),
                    color = c.recordingRed,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Text(
                text = note.preview,
                color = c.dim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )
    }
}
