package com.trailmix.app.ui.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun HomeScreen(
    onNewCapture: () -> Unit,
    onCaptureMeeting: (String) -> Unit,
    onOpenMeetings: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val calendarGranted by viewModel.calendarGranted.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshUpcoming() }

    val c = TrailMix.colors

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
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(c.card)
                        .clickable(onClick = onOpenSettings),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
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
                    SectionLabel(
                        text = "Notes",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
                    )
                }
                if (notes.isEmpty()) {
                    item {
                        Text(
                            text = "No notes yet — tap + to start a capture.",
                            color = c.dim,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
                items(notes, key = { it.id }) { note ->
                    NoteRow(note = note, onClick = { onOpenNote(note.id) })
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

@Composable
private fun NoteRow(note: NoteEntity, onClick: () -> Unit) {
    val c = TrailMix.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )
    }
}
