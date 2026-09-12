package com.trailmix.app.ui.home

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onNewCapture: () -> Unit,
    onCaptureMeeting: (String) -> Unit,
    onOpenMeetings: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** REL-06: open the Recently deleted recovery screen. */
    onOpenRecentlyDeleted: () -> Unit,
    /** CAP-10: reopen a capture that's still recording in the background. */
    onOpenActiveCapture: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val meetingsOnly by viewModel.meetingsOnly.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val deletedCount by viewModel.deletedCount.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val calendarGranted by viewModel.calendarGranted.collectAsStateWithLifecycle()
    val activeCapture by viewModel.activeCapture.collectAsStateWithLifecycle()
    // REL-09: a capture the app never got to finish, still on disk.
    val pendingRecovery by viewModel.pendingRecovery.collectAsStateWithLifecycle()
    val recovering by viewModel.recovering.collectAsStateWithLifecycle()

    // UX-10: non-null selectedIds = selection mode. Back exits it instead of the app.
    val selecting = selectedIds != null
    BackHandler(enabled = selecting) { viewModel.exitSelectionMode() }
    val scope = rememberCoroutineScope()
    var confirmDeleteSelected by remember { mutableStateOf(false) }

    // Export-format dropdown: a share action (single note or the bulk selection) waiting on
    // the one-off format picker before its intent is actually sent.
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }

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

    // REL-09: a recovered capture merged into a note — open it, same as End & Merge does.
    LaunchedEffect(Unit) {
        viewModel.recoveredNoteId.collect { id -> onOpenNote(id) }
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
            // Wordmark + avatar-slot (opens Settings — no accounts in a local-only app).
            // UX-10: in selection mode this row becomes "N selected" + Cancel instead.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) {
                    Text(
                        text = "${selectedIds.orEmpty().size} selected",
                        color = c.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Cancel",
                        color = c.dim,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .clickable { viewModel.exitSelectionMode() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                } else {
                    Text(
                        text = "TrailMix",
                        color = c.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // UX-09 (revised): bare hamburger menu button — theme-adaptive tint,
                    // no circle background (user request); CircleShape clip keeps the
                    // ripple round over the 34dp touch target.
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
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
                        text = (if (active.paused) "Paused" else "Recording") +
                            (active.meetingTitle?.let { " · $it" } ?: ""),
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

            // Notes header + "Meetings" filter chip (CAL-05) on one line — the chip
            // narrows the list to notes linked to a calendar meeting, composing with search.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(text = if (searching) "Results" else "Notes")
                Text(
                    text = "Meetings",
                    color = if (meetingsOnly) Color.White else c.dim,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (meetingsOnly) c.amber else c.card)
                        .clickable { viewModel.setMeetingsOnly(!meetingsOnly) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (notes.isEmpty()) {
                    item {
                        Text(
                            text = when {
                                searching -> "No notes match your search."
                                meetingsOnly -> "No notes are linked to a calendar meeting yet."
                                else -> "No notes yet — tap + to start a capture."
                            },
                            color = c.dim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
                items(notes, key = { it.id }) { row ->
                    NoteRow(
                        row = row,
                        // UX-10: null when not in selection mode; row shows an indicator
                        // and taps toggle instead of opening while selecting.
                        selected = selectedIds?.contains(row.id),
                        onClick = {
                            if (selecting) viewModel.toggleSelected(row.id) else onOpenNote(row.id)
                        },
                        onLongClick = {
                            if (selecting) viewModel.toggleSelected(row.id) else contextMenuNote = row.note
                        },
                    )
                }
                // REL-06: entry to the recovery screen, only when something is in it.
                if (deletedCount > 0 && !searching && !selecting) {
                    item {
                        Text(
                            text = "Recently deleted ($deletedCount)",
                            color = c.dim,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenRecentlyDeleted)
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }

        // FAB — 56dp, 16dp radius, amber, pinned 24dp from bottom-right.
        // Hidden in selection mode (UX-10): the bottom action bar takes its place.
        if (!selecting) {
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
        }

        // UX-10: bottom Delete / Share bar while selecting. Delete confirms first and is
        // a soft delete (each note recoverable via Recently deleted, REL-06); Share sends
        // the selected notes' combined Markdown through the system sheet.
        if (selecting) {
            val count = selectedIds.orEmpty().size
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .shadow(8.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.card),
            ) {
                Text(
                    text = "Delete",
                    color = if (count > 0) c.recordingRed else c.dim.copy(alpha = 0.5f),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = count > 0) { confirmDeleteSelected = true }
                        .padding(vertical = 15.dp),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(c.border),
                )
                Text(
                    text = "Share",
                    color = if (count > 0) c.amber else c.dim.copy(alpha = 0.5f),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = count > 0) { pendingShare = PendingShare.Bulk(count) }
                        .padding(vertical = 15.dp),
                )
            }
        }

        if (confirmDeleteSelected) {
            val count = selectedIds.orEmpty().size
            AlertDialog(
                onDismissRequest = { confirmDeleteSelected = false },
                containerColor = c.card,
                title = {
                    Text(
                        text = "Delete $count note${if (count == 1) "" else "s"}?",
                        color = c.text,
                        fontSize = 17.sp,
                    )
                },
                text = {
                    Text(
                        text = "They'll move to Recently deleted and stay recoverable " +
                            "for 1 day. Exported copies are removed now.",
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
                        modifier = Modifier
                            .clickable {
                                confirmDeleteSelected = false
                                viewModel.deleteSelected()
                            }
                            .padding(8.dp),
                    )
                },
                dismissButton = {
                    Text(
                        text = "Cancel",
                        color = c.dim,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { confirmDeleteSelected = false }
                            .padding(8.dp),
                    )
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }

    // REL-09: offer back a capture that died with the process. Suppressed while a recovery
    // merge is already running so the two dialogs can't stack.
    pendingRecovery?.takeIf { !recovering }?.let { pending ->
        CrashRecoveryDialog(
            pending = pending,
            onContinue = {
                viewModel.continueRecovered()
                onOpenActiveCapture()
            },
            onComplete = { viewModel.completeRecovered() },
            onDiscard = { viewModel.discardRecovered() },
            onLater = { viewModel.dismissRecovery() },
        )
    }

    if (recovering) {
        RecoveryProgressDialog()
    }

    // Long-press context menu (CAP-05, slimmed by INT-02/UX-08 in v1.7.0): Delete / Share.
    // UX-10 adds Select multiple, which enters selection mode seeded with this note.
    contextMenuNote?.let { note ->
        NoteContextMenu(
            note = note,
            onDismiss = { contextMenuNote = null },
            onSelectMultiple = {
                contextMenuNote = null
                viewModel.enterSelectionMode(note.id)
            },
            onDelete = {
                contextMenuNote = null
                viewModel.deleteNote(note.id)
            },
            onShare = {
                contextMenuNote = null
                pendingShare = PendingShare.Single(note)
            },
        )
    }

    pendingShare?.let { share ->
        ExportFormatPickerDialog(
            initialFormat = defaultExportFormat,
            onConfirm = { format ->
                pendingShare = null
                when (share) {
                    is PendingShare.Single -> {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, share.note.title)
                            putExtra(Intent.EXTRA_TEXT, share.note.toMarkdown(format = format))
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share note"))
                    }
                    is PendingShare.Bulk -> {
                        scope.launch {
                            val markdown = viewModel.selectedMarkdown(format)
                            viewModel.exitSelectionMode()
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "${share.count} notes from TrailMix")
                                putExtra(Intent.EXTRA_TEXT, markdown)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share notes"))
                        }
                    }
                }
            },
            onDismiss = { pendingShare = null },
        )
    }
}

/** A share action queued behind the export-format dropdown's one-off picker. */
private sealed class PendingShare {
    data class Single(val note: NoteEntity) : PendingShare()
    data class Bulk(val count: Int) : PendingShare()
}

/**
 * REL-09: the first thing you see after TrailMix died mid-capture.
 *
 * The tone is deliberate. A crash during a recording is alarming precisely because the user
 * has no way to know whether their hour of notes still exists, so the dialog leads with the
 * answer — it was saved as it ran — before offering anything. The two real choices are the
 * ones the situation actually poses: the meeting is still going (Continue), or it isn't
 * (Save as note).
 *
 * Discard is present but last and destructive-coloured, behind its own confirm. Dismissing
 * the dialog is **Later**, not a decision: it keeps the journal and re-offers it next launch,
 * because an accidental tap outside must never be how someone loses a transcript.
 */
@Composable
private fun CrashRecoveryDialog(
    pending: com.trailmix.app.data.speech.PendingJournal,
    onContinue: () -> Unit,
    onComplete: () -> Unit,
    onDiscard: () -> Unit,
    onLater: () -> Unit,
) {
    val c = TrailMix.colors
    var confirmDiscard by remember { mutableStateOf(false) }
    val session = pending.session

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = c.card,
            title = { Text("Discard this capture?", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    // No Recently deleted safety net here: this transcript was never a note,
                    // so there is nothing to restore it from. Say so plainly.
                    "The recovered transcript will be deleted permanently. It was never " +
                        "saved as a note, so this can't be undone.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Discard",
                    color = c.recordingRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { confirmDiscard = false; onDiscard() }.padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Keep it",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmDiscard = false }.padding(8.dp),
                )
            },
        )
        return
    }

    Dialog(onDismissRequest = onLater) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(vertical = 8.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    text = "Unfinished capture recovered",
                    color = c.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = recoverySummary(session),
                    color = c.amber,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "TrailMix closed before this capture was saved. The transcript " +
                        "was written as it ran, so it's all still here.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
            ContextMenuRow(
                label = "Continue capture",
                hint = "Pick up recording where it left off",
            ) { onContinue() }
            ContextMenuRow(
                label = "Save as note",
                hint = "Merge what was captured and finish now",
            ) { onComplete() }
            ContextMenuRow(label = "Later", hint = "Ask again next time you open TrailMix") { onLater() }
            ContextMenuRow(label = "Discard", destructive = true) { confirmDiscard = true }
        }
    }
}

/** e.g. "Jul 31, 3:04 PM · 42:15 · 318 lines" — enough to recognise which session this was. */
private fun recoverySummary(session: com.trailmix.app.data.speech.CaptureJournal.RecoveredSession): String {
    val parts = mutableListOf<String>()
    if (session.startedAtEpochMs > 0) {
        parts += SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
            .format(Date(session.startedAtEpochMs))
    }
    if (session.durationMs > 0) {
        parts += com.trailmix.app.data.ai.TranscriptCoverage.formatSeconds(
            (session.durationMs / 1000).toInt(),
        )
    }
    val lines = session.transcript.size
    if (lines > 0) parts += "$lines line${if (lines == 1) "" else "s"}"
    if (session.typedFragments.isNotBlank()) parts += "typed notes"
    return parts.joinToString(" · ")
}

/**
 * REL-09: shown while a recovered capture is merging. Deliberately not dismissable — the
 * merge is a chunked on-device model pass that can run for minutes on a long session, and
 * letting the dialog close would leave no sign that anything was happening.
 */
@Composable
private fun RecoveryProgressDialog() {
    val c = TrailMix.colors
    Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(c.card)
                .padding(24.dp),
        ) {
            Text(
                text = "Rebuilding your note…",
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Summarizing on-device. A long capture can take a few minutes.",
                color = c.dim,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
    onSelectMultiple: () -> Unit,
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
                ContextMenuRow(label = "Select multiple") { onSelectMultiple() }
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
                    // UX-16: this said "This can't be undone", which stopped being true at
                    // REL-06 (v1.8.0) when delete became a *soft* delete with a 1-day
                    // recovery window. The multi-select dialog above was updated then and
                    // this single-note one was missed, so the same action was described two
                    // contradictory ways. Wording deliberately mirrors that dialog — the
                    // scary-but-wrong version risks talking someone out of a reversible
                    // action, and would be far worse if it ever made them trust it.
                    "It'll move to Recently deleted and stay recoverable for 1 day. " +
                        "Exported copies are removed now.",
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

/** [selected] is null outside selection mode; a Boolean shows the UX-10 indicator circle. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    row: HomeNote,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val note = row.note
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // UX-10: selection indicator — filled amber check when selected, hollow
            // circle otherwise. Only present in selection mode.
            if (selected != null) {
                Box(
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .let {
                            if (selected) {
                                it.background(c.amber)
                            } else {
                                it.border(1.5.dp, c.dim, CircleShape)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    color = c.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.preview,
                    color = c.dim,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
                // UX-12: creation date & time on every row; CAL-05 adds a meeting tag
                // on notes linked to a calendar event. UX-18: the label is formatted once
                // per note off the main thread, not per composition.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.createdLabel,
                        color = c.dim.copy(alpha = 0.75f),
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    if (note.meetingTitle != null) {
                        Text(
                            text = "Meeting",
                            color = c.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(start = 8.dp, top = 3.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(c.amber.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )
    }
}
