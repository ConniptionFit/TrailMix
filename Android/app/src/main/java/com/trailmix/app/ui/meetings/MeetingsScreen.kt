package com.trailmix.app.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.trailmix.app.R
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmListRow
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.home.StartCaptureDialog
import com.trailmix.app.ui.home.timeLabel
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeetingsViewModel @Inject constructor(
    private val meetingSource: UpcomingMeetingSource,
    notesRepository: NotesRepository,
) : ViewModel() {
    private val _meetings = MutableStateFlow<List<UpcomingMeeting>>(emptyList())
    val meetings: StateFlow<List<UpcomingMeeting>> = _meetings.asStateFlow()

    /** Notes the "has a note" check looks through. */
    val notes: StateFlow<List<NoteEntity>> = notesRepository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { _meetings.value = meetingSource.listUpcoming(days = 7) }
    }
}

/**
 * M1: read-only list of the next 7 days of calendar events, grouped by day. A meeting that
 * already has a note opens it; any other starts a note, asking first when it is far off.
 */
@Composable
fun MeetingsScreen(
    onBack: () -> Unit,
    onStartCapture: (String) -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: MeetingsViewModel = hiltViewModel(),
) {
    val meetings by viewModel.meetings.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val items = remember(meetings, notes) { groupMeetingsByDay(meetings, notes) }

    // Same rule as the Home card: an imminent or ongoing meeting starts at once; a further-out
    // one asks first.
    var pendingStart by remember { mutableStateOf<UpcomingMeeting?>(null) }
    pendingStart?.let { meeting ->
        StartCaptureDialog(
            meeting = meeting,
            onConfirm = {
                pendingStart = null
                onStartCapture(meeting.title)
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
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            TmTopBar(
                title = stringResource(R.string.meetings_title),
                subtitle = stringResource(R.string.meetings_subtitle),
                navigation = { TmBackButton(onClick = onBack, contentDescription = stringResource(R.string.action_back)) },
            )
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.meetings_empty),
                    style = TrailMix.type.body,
                    color = c.dim,
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
                )
            }
            LazyColumn(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f)) {
                items(
                    items,
                    key = { item ->
                        when (item) {
                            is MeetingsItem.Day -> "day:${item.label}"
                            is MeetingsItem.Row -> "${item.row.meeting.eventId}-${item.row.meeting.beginEpochMs}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is MeetingsItem.Day -> Text(
                            text = item.label,
                            style = TrailMix.type.overline,
                            color = c.dim,
                            modifier = Modifier.padding(start = TmSpacing.l, end = TmSpacing.l, top = TmSpacing.l, bottom = TmSpacing.xs),
                        )

                        is MeetingsItem.Row -> MeetingListRow(
                            row = item.row,
                            onClick = {
                                val meeting = item.row.meeting
                                val noteId = item.row.noteId
                                when {
                                    noteId != null -> onOpenNote(noteId)
                                    meeting.minutesUntilStart > 5 -> pendingStart = meeting
                                    else -> onStartCapture(meeting.title)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeetingListRow(row: MeetingRow, onClick: () -> Unit) {
    val c = TrailMix.colors
    val meeting = row.meeting
    val ongoing = meeting.isOngoingAt()
    val people = meeting.attendeeCount?.let { pluralStringResource(R.plurals.meetings_people, it, it) }
    TmListRow(
        title = meeting.title,
        subtitle = people,
        onClick = onClick,
        leading = {
            Column(modifier = Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.Start) {
                Text(timeLabel(meeting.beginEpochMs), style = TrailMix.type.mono, color = c.text)
                if (ongoing) {
                    // "Now" is an ink badge: teal is reserved for what was said in a recording.
                    Row(
                        modifier = Modifier
                            .padding(top = TmSpacing.xs)
                            .clip(CircleShape)
                            .background(c.text)
                            .padding(horizontal = TmSpacing.s, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(stringResource(R.string.meetings_now), style = TrailMix.type.caption, color = c.background)
                    }
                }
            }
        },
        trailing = {
            if (row.noteId != null) {
                TmIcon(TmIcons.Subject, contentDescription = stringResource(R.string.meetings_open_note), tint = c.dim)
            } else {
                TmIcon(TmIcons.Mic, contentDescription = stringResource(R.string.meetings_start_note), tint = c.dim)
            }
        },
    )
}
