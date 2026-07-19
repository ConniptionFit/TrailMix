package com.trailmix.app.ui.home

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNewCapture: () -> Unit,
    onCaptureMeeting: (String) -> Unit,
    onOpenMeetings: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** CAP-10: reopen a capture that's still recording in the background. */
    onOpenActiveCapture: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val calendarGranted by viewModel.calendarGranted.collectAsStateWithLifecycle()
    val activeCapture by viewModel.activeCapture.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshUpcoming() }

    val c = TrailMix.colors
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Long-press context menu state (CAP-05): which note's menu is open. Since v1.7.0
    // (INT-02/UX-08) the menu is just Delete + Share — per-note Move was replaced by the
    // global Export location + auto-migration, and "Open file location" by the Settings
    // "Open folder" button.
    var contextMenuNote by remember { mutableStateOf<NoteEntity?>(null) }

    // Tapping the next meeting: imminent (≤5 min) or ongoing starts capture
    // immediately; further out asks first.
    var pendingStart by remember { mutableStateOf<UpcomingMeeting?>(null) }
    pendingStart?.let { meeting ->
        StartCaptureDialog(
            meeting = meeting,
            onConfirm = {
                pendingStart = null
                onCaptureMeeting(meeting.title)
            },
            onDismiss = { pendingStart = null },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Wordmark + avatar-slot (opens Settings — no accounts in a local-only app)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "TrailMix",
                    color = c.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // UX-09 (revised): hamburger menu button — theme-adaptive (c.card/c.text
                // flip with light/dark), replacing the blank avatar-slot circle.
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c.card)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Settings",
                        tint = c.text,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // In-progress transcription chip (CAP-10) — recording continues in the
            // background even after leaving Capture; tap to jump straight back in.
            activeCapture?.let { active ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.amber)
                        .clickable(onClick = onOpenActiveCapture)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Recording" + (active.meetingTitle?.let { " · $it" } ?: ""),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = active.elapsedLabel,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            val searching = query.isNotBlank()

            // Upcoming meeting sits above the search bar (user request); it still gives
            // way to results while a search is active.
            if (!searching) {
                SectionLabel(
                    text = "Upcoming — from calendar",
                    modifier = Modifier
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)
                        .clickable {
                            if (calendarGranted) {
                                onOpenMeetings()
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                        },
                )
                UpcomingCard(
                    granted = calendarGranted,
                    title = upcoming?.title,
                    time = upcoming?.timeLabel,
                    onEnable = { permissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                    onTapMeeting = upcoming?.let { meeting ->
                        {
                            if (meeting.minutesUntilStart > 5) {
                                pendingStart = meeting
                            } else {
                                onCaptureMeeting(meeting.title)
                            }
                        }
                    },
                )
            }

            // Search (UX-13): live keyword + date filter over the notes list. Dates match
            // in common spellings ("jul 18", "7/18/2026", "2026-07-18") via NoteSearch.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.card)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = c.dim,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.searchQuery.value = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.amber),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Search notes — keywords or dates",
                                color = c.dim,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = c.dim,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { viewModel.searchQuery.value = "" },
                    )
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    SectionLabel(
                        text = if (searching) "Results" else "Notes",
                        modifier = Modifier.padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 10.dp,
                            bottom = 4.dp,
                        ),
                    )
                }
                if (notes.isEmpty()) {
                    item {
                        Text(
                            text = if (searching) {
                                "No notes match your search."
                            } else {
                                "No notes yet — tap + to start a capture."
                            },
                            color = c.dim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
                items(notes, key = { it.id }) { note ->
                    NoteRow(
                        note = note,
                        onClick = { onOpenNote(note.id) },
                        onLongClick = { contextMenuNote = note },
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        // FAB — 56dp, 16dp radius, amber, pinned 24dp from bottom-right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(56.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(c.amber)
                .clickable(onClick = onNewCapture),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New note",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }

    // Long-press context menu (CAP-05, slimmed by INT-02/UX-08 in v1.7.0): Delete / Share.
    contextMenuNote?.let { note ->
        NoteContextMenu(
            note = note,
            onDismiss = { contextMenuNote = null },
            onDelete = {
                contextMenuNote = null
                viewModel.deleteNote(note.id)
            },
            onShare = {
                contextMenuNote = null
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, note.title)
                    putExtra(Intent.EXTRA_TEXT, note.toMarkdown())
                }
                context.startActivity(Intent.createChooser(sendIntent, "Share note"))
            },
        )
    }
}

/**
 * Long-press context menu on a Home note row (CAP-05 Part 1): Delete (with confirm, cascades
 * to the tracked export file) and Share (reuses the UX-03 ACTION_SEND flow). v1.7.0 removed
 * the other two actions by user request: "Move" (INT-02 — the single global Export location
 * with auto-migration replaces per-note re-export) and "Open file location" (UX-08 — replaced
 * by the "Open folder" button next to the Export location in Settings).
 */
@Composable
private fun NoteContextMenu(
    note: NoteEntity,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    val c = TrailMix.colors
    var confirmDelete by remember { mutableStateOf(false) }

    if (!confirmDelete) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.card)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = note.title,
                    color = c.dim,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
                ContextMenuRow(label = "Delete", destructive = true) { confirmDelete = true }
                ContextMenuRow(label = "Share") { onShare() }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = c.card,
            title = { Text("Delete this note?", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    "This can't be undone. If it's been exported to your export location, " +
                        "TrailMix will try to remove that copy too.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Delete",
                    color = c.recordingRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDelete() }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Cancel",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
                )
            },
        )
    }
}

@Composable
private fun ContextMenuRow(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    hint: String? = null,
    onClick: () -> Unit,
) {
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> c.dim.copy(alpha = 0.5f)
                destructive -> c.recordingRed
                else -> c.text
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (hint != null) {
            Text(text = hint, color = c.dim, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Confirmation for starting a capture ahead of a not-yet-imminent meeting
 * (>5 minutes out). Shared by Home and the meetings list.
 */
@Composable
fun StartCaptureDialog(
    meeting: UpcomingMeeting,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(meeting.title, color = c.text, fontSize = 17.sp) },
        text = {
            Text(
                text = "This meeting doesn't start for another " +
                    "${meeting.minutesUntilStart} minutes (${meeting.timeLabel}). " +
                    "Start capturing now anyway?",
                color = c.dim,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
            )
        },
        confirmButton = {
            Text(
                text = "Start now",
                color = c.amber,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onConfirm)
                    .padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "Wait",
                color = c.dim,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        },
    )
}

@Composable
private fun UpcomingCard(
    granted: Boolean,
    title: String?,
    time: String?,
    onEnable: () -> Unit,
    onTapMeeting: (() -> Unit)?,
) {
    val c = TrailMix.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.card)
            .let {
                when {
                    !granted -> it.clickable(onClick = onEnable)
                    onTapMeeting != null -> it.clickable(onClick = onTapMeeting)
                    else -> it
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            !granted -> Text(
                text = "Tap to show your next meeting (read-only calendar access)",
                color = c.dim,
                fontSize = 13.sp,
            )
            title == null -> Text(
                text = "Nothing in the next 24 hours",
                color = c.dim,
                fontSize = 13.sp,
            )
            else -> {
                Text(
                    text = title,
                    color = c.text,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = time.orEmpty(),
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: NoteEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Text(
                text = note.title,
                color = c.text,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = note.preview,
                color = c.dim,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            // UX-12: creation date & time on every row.
            val createdLabel = remember(note.createdAtEpochMs) {
                SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
                    .format(Date(note.createdAtEpochMs))
            }
            Text(
                text = createdLabel,
                color = c.dim.copy(alpha = 0.75f),
                fontSize = 11.5.sp,
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
