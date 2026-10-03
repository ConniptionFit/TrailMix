package com.trailmix.app.ui.home

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.ui.components.ActiveCaptureCard
import com.trailmix.app.ui.components.TmButtonIcon
import com.trailmix.app.ui.components.TmCloseButton
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmFilterChip
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmMenuItem
import com.trailmix.app.ui.components.TmOutlinedButton
import com.trailmix.app.ui.components.TmOverflowMenu
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.components.showUndo
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.launch

/** Cap on the list column so rows don't stretch across a tablet or an unfolded phone. */
private val ListMaxWidth = 640.dp

@Composable
fun HomeScreen(
    onNewCapture: () -> Unit,
    onCaptureMeeting: (String) -> Unit,
    onOpenMeetings: () -> Unit,
    onOpenNote: (Long) -> Unit,
    /** UX-19/UX-20: open a note's transcript scrolled to the moment a search matched. */
    onOpenTranscriptMoment: (noteId: Long, label: String) -> Unit,
    /** AI-10: open a chat spanning every currently-selected note (2+ required). */
    onOpenCrossNoteChat: (Set<Long>) -> Unit,
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
    val unexported by viewModel.unexportedCount.collectAsStateWithLifecycle()
    val repairing by viewModel.repairingExports.collectAsStateWithLifecycle()
    val hasActiveCapture by viewModel.hasActiveCapture.collectAsStateWithLifecycle()
    // PERF-04: the 1 Hz timer and 10 Hz level are collected inside ActiveCaptureCard, never here.
    // REL-09: a capture the app never got to finish, still on disk.
    val pendingRecovery by viewModel.pendingRecovery.collectAsStateWithLifecycle()
    val recovering by viewModel.recovering.collectAsStateWithLifecycle()
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()

    // UX-10: non-null selectedIds = selection mode. Back exits it instead of the app.
    val selecting = selectedIds != null
    val selectedCount = selectedIds.orEmpty().size
    BackHandler(enabled = selecting) { viewModel.exitSelectionMode() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = TrailMix.colors
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    var showCalendarRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshUpcoming() }

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // REL-09: a recovered capture merged into a note, so open it the way End & Merge does.
    LaunchedEffect(Unit) {
        viewModel.recoveredNoteId.collect { id -> onOpenNote(id) }
    }

    // B3: delete is soft, so there is no confirm dialog; the snackbar offers Undo for 8 s.
    LaunchedEffect(Unit) {
        viewModel.undoableDeletes.collect { deleted ->
            val res = context.resources
            val message = res.getQuantityString(R.plurals.home_deleted, deleted.ids.size, deleted.ids.size) +
                if (deleted.fileFailures > 0) {
                    res.getQuantityString(R.plurals.home_deleted_files_failed, deleted.fileFailures, deleted.fileFailures)
                } else {
                    ""
                }
            if (snackbarHostState.showUndo(message, res.getString(R.string.action_undo))) {
                viewModel.restore(deleted.ids)
            }
        }
    }

    // Tapping the next meeting: imminent (5 min or less) or ongoing starts capture at once;
    // further out asks first.
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
    if (showCalendarRationale) {
        TmConfirmDialog(
            title = stringResource(R.string.home_calendar_title),
            body = stringResource(R.string.home_calendar_body),
            confirmLabel = stringResource(R.string.home_calendar_continue),
            onConfirm = {
                showCalendarRationale = false
                permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
            },
            onDismiss = { showCalendarRationale = false },
        )
    }

    val searching = query.isNotBlank()
    val firstRun = notes.isEmpty() && deletedCount == 0 && !searching && !meetingsOnly

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (selecting) {
                TmTopBar(
                    title = pluralStringResource(R.plurals.home_selected, selectedCount, selectedCount),
                    navigation = {
                        TmCloseButton(
                            onClick = viewModel::exitSelectionMode,
                            contentDescription = stringResource(R.string.home_selection_cancel),
                        )
                    },
                    actions = { TmTextButton(stringResource(R.string.home_select_all), onClick = viewModel::selectAll) },
                )
            } else {
                TmTopBar(
                    title = stringResource(R.string.home_title),
                    actions = {
                        TmOverflowMenu(
                            contentDescription = stringResource(R.string.home_more),
                            items = listOf(
                                TmMenuItem(stringResource(R.string.home_menu_meetings), onOpenMeetings),
                                TmMenuItem(
                                    label = stringResource(R.string.home_menu_deleted),
                                    onClick = onOpenRecentlyDeleted,
                                    badge = deletedCount.takeIf { it > 0 }?.toString(),
                                ),
                                TmMenuItem(stringResource(R.string.home_menu_settings), onOpenSettings),
                            ),
                        )
                    },
                )
            }

            val listState = rememberLazyListState()
            // UX-21: LazyColumn anchors scroll to the previously-first visible item by key, so a
            // newly inserted (newer) note lands above that anchor and reads like a missing note.
            // Only correct it when the user was already at or near the top.
            val newestNoteId = notes.firstOrNull()?.note?.id
            LaunchedEffect(newestNoteId) {
                if (newestNoteId != null && listState.firstVisibleItemIndex <= 1) {
                    listState.animateScrollToItem(0)
                }
            }
            val listItems = remember(notes, searching) {
                if (searching) notes.map { HomeListItem.NoteRow(it) } else groupByDay(notes, System.currentTimeMillis())
            }
            val requestCalendar = { showCalendarRationale = true }

            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = ListMaxWidth).fillMaxWidth().weight(1f),
            ) {
                if (!selecting) {
                    item(key = "active") {
                        ActiveCaptureCard(
                            activeCapture = viewModel.activeCapture,
                            level = viewModel.level,
                            mergeStatus = viewModel.mergeStatus,
                            onOpen = onOpenActiveCapture,
                            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                        )
                    }
                }
                if (firstRun) {
                    item(key = "first-run") {
                        FirstRunContent(
                            calendarGranted = calendarGranted,
                            onStart = onNewCapture,
                            onShowMeeting = requestCalendar,
                        )
                    }
                } else {
                    if (!searching && !selecting) {
                        item(key = "upcoming") {
                            val meeting = upcoming
                            when {
                                calendarGranted && meeting != null -> UpcomingMeetingCard(
                                    meeting = meeting,
                                    onStart = {
                                        if (meeting.minutesUntilStart > 5) pendingStart = meeting else onCaptureMeeting(meeting.title)
                                    },
                                    onSeeAll = onOpenMeetings,
                                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                                )

                                !calendarGranted -> TmOutlinedButton(
                                    label = stringResource(R.string.home_show_next_meeting),
                                    onClick = requestCalendar,
                                    icon = TmButtonIcon.Drawable(TmIcons.Calendar),
                                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                                )
                            }
                        }
                    }
                    item(key = "search") {
                        SearchField(
                            query = query,
                            onQueryChange = { viewModel.searchQuery.value = it },
                            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                        )
                    }
                    item(key = "filters") {
                        Row(
                            modifier = Modifier.padding(horizontal = TmSpacing.l),
                            horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TmFilterChip(
                                label = stringResource(R.string.home_filter_all),
                                selected = !meetingsOnly,
                                onClick = { viewModel.setMeetingsOnly(false) },
                            )
                            TmFilterChip(
                                label = stringResource(R.string.home_filter_meetings),
                                selected = meetingsOnly,
                                onClick = { viewModel.setMeetingsOnly(true) },
                                leadingDrawable = TmIcons.Calendar,
                            )
                        }
                    }
                    if (unexported > 0 && !searching && !selecting) {
                        item(key = "export-health") {
                            ExportHealthLine(
                                count = unexported,
                                repairing = repairing,
                                onExport = viewModel::exportMissing,
                                modifier = Modifier.padding(horizontal = TmSpacing.l),
                            )
                        }
                    }
                    if (searching && notes.isNotEmpty()) {
                        item(key = "search-count") {
                            Text(
                                text = pluralStringResource(R.plurals.home_search_count, notes.size, notes.size).uppercase(),
                                style = TrailMix.type.overline,
                                color = c.dim,
                                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
                            )
                        }
                    }
                    if (notes.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(
                                    when {
                                        searching -> R.string.home_empty_search
                                        meetingsOnly -> R.string.home_empty_meetings
                                        else -> R.string.home_empty
                                    },
                                ),
                                style = TrailMix.type.body,
                                color = c.dim,
                                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
                            )
                        }
                    }
                    items(
                        listItems,
                        key = { item ->
                            when (item) {
                                is HomeListItem.DayHeader -> "day:${item.label}"
                                is HomeListItem.NoteRow -> item.note.id
                            }
                        },
                    ) { item ->
                        when (item) {
                            is HomeListItem.DayHeader -> {
                                DayHeaderRow(item)
                            }

                            is HomeListItem.NoteRow -> {
                                val row = item.note
                                NoteListRow(
                                    row = row,
                                    searching = searching,
                                    // UX-10: null outside selection mode; taps toggle while selecting.
                                    selected = selectedIds?.contains(row.id),
                                    onClick = {
                                        if (selecting) viewModel.toggleSelected(row.id) else onOpenNote(row.id)
                                    },
                                    onLongClick = {
                                        if (selecting) viewModel.toggleSelected(row.id) else viewModel.enterSelectionMode(row.id)
                                    },
                                    onMomentClick = { label -> onOpenTranscriptMoment(row.id, label) },
                                )
                            }
                        }
                    }
                }
                item(key = "end-space") { Spacer(Modifier.height(96.dp)) }
            }
        }

        // One capture at a time, and the snackbar owns the corner while it is up.
        val snackbarUp = snackbarHostState.currentSnackbarData != null
        if (!selecting && !hasActiveCapture && !snackbarUp && !firstRun) {
            ExtendedFloatingActionButton(
                onClick = onNewCapture,
                modifier = Modifier.align(Alignment.BottomEnd).padding(TmSpacing.l),
                shape = TrailMix.shapes.large,
                containerColor = c.text,
                contentColor = c.background,
            ) {
                TmIcon(TmIcons.Mic, contentDescription = null, tint = c.background)
                Text(
                    stringResource(R.string.home_new_note),
                    style = TrailMix.type.label,
                    modifier = Modifier.padding(start = TmSpacing.s),
                )
            }
        }

        // UX-10: Share / Chat / Delete with an icon and label at equal weight.
        if (selecting) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = ListMaxWidth)
                    .fillMaxWidth()
                    .padding(TmSpacing.m)
                    .background(c.card, TrailMix.shapes.large),
            ) {
                SelectionAction(
                    label = stringResource(R.string.home_share),
                    enabled = selectedCount > 0,
                    onClick = { pendingShare = PendingShare(selectedCount) },
                ) { tint -> Icon(Icons.Filled.Share, contentDescription = null, tint = tint) }
                SelectionAction(
                    label = if (selectedCount >= 2) {
                        stringResource(R.string.home_chat_across, selectedCount)
                    } else {
                        stringResource(R.string.home_chat_select_two)
                    },
                    enabled = selectedCount >= 2,
                    onClick = {
                        val ids = selectedIds.orEmpty()
                        viewModel.exitSelectionMode()
                        onOpenCrossNoteChat(ids)
                    },
                ) { tint -> TmIcon(TmIcons.Chat, contentDescription = null, tint = tint) }
                SelectionAction(
                    label = stringResource(R.string.home_delete),
                    enabled = selectedCount > 0,
                    destructive = true,
                    onClick = viewModel::deleteSelected,
                ) { tint -> Icon(Icons.Filled.Delete, contentDescription = null, tint = tint) }
            }
        }

        TmSnackbarHost(
            state = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (selecting) 72.dp else 0.dp),
        )
    }

    // REL-09: offer back a capture that died with the process. While the rebuild runs, the
    // Building card on Home is the progress, so the sheet stays away.
    pendingRecovery?.takeIf { !recovering }?.let { pending ->
        RecoverySheet(
            pending = pending,
            onBuild = { viewModel.completeRecovered() },
            onKeep = {
                viewModel.continueRecovered()
                onOpenActiveCapture()
            },
            onDiscard = { viewModel.discardRecovered() },
            onLater = { viewModel.dismissRecovery() },
        )
    }

    pendingShare?.let { share ->
        ExportFormatPickerDialog(
            initialFormat = defaultExportFormat,
            onConfirm = { format ->
                pendingShare = null
                scope.launch {
                    val markdown = viewModel.selectedMarkdown(format)
                    viewModel.exitSelectionMode()
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.home_share_notes_subject, share.count))
                        putExtra(Intent.EXTRA_TEXT, markdown)
                    }
                    val chooser = if (share.count == 1) R.string.home_share_chooser_note else R.string.home_share_chooser_notes
                    context.startActivity(Intent.createChooser(sendIntent, context.getString(chooser)))
                }
            },
            onDismiss = { pendingShare = null },
        )
    }
}

/** A share waiting on the export-format picker; [count] is only for the email subject. */
private data class PendingShare(val count: Int)

@Composable
private fun RowScope.SelectionAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    icon: @Composable (tint: Color) -> Unit,
) {
    val c = TrailMix.colors
    val tint = when {
        !enabled -> c.dim
        destructive -> c.recordingRed
        else -> c.text
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = TmSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(tint)
        Text(label, style = TrailMix.type.caption, color = tint, maxLines = 1)
    }
}
