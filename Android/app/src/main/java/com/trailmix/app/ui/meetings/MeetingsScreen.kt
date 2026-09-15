package com.trailmix.app.ui.meetings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.ui.components.BackTitleBar
import com.trailmix.app.ui.home.StartCaptureDialog
import com.trailmix.app.ui.theme.TrailMix
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeetingsViewModel @Inject constructor(
    private val meetingSource: UpcomingMeetingSource,
) : ViewModel() {
    private val _meetings = MutableStateFlow<List<UpcomingMeeting>>(emptyList())
    val meetings: StateFlow<List<UpcomingMeeting>> = _meetings.asStateFlow()

    init {
        viewModelScope.launch { _meetings.value = meetingSource.listUpcoming(days = 7) }
    }
}

/** Read-only list of the next 7 days of calendar events; tap one to capture it. */
@Composable
fun MeetingsScreen(
    onBack: () -> Unit,
    onStartCapture: (String) -> Unit,
    viewModel: MeetingsViewModel = hiltViewModel(),
) {
    val meetings by viewModel.meetings.collectAsStateWithLifecycle()
    val c = TrailMix.colors

    // Same rule as the Home card: an imminent/ongoing meeting starts
    // immediately; a further-out one asks first.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        BackTitleBar(title = "Upcoming meetings", onBack = onBack)
        if (meetings.isEmpty()) {
            Text(
                text = "Nothing scheduled in the next 7 days.",
                color = c.dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn {
            items(meetings) { meeting ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (meeting.minutesUntilStart > 5) {
                                pendingStart = meeting
                            } else {
                                onStartCapture(meeting.title)
                            }
                        }
                        .padding(horizontal = 20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = meeting.title,
                            color = c.text,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = if (meeting.isOngoingAt()) "now" else meeting.timeLabel,
                            color = if (meeting.isOngoingAt()) c.teal else c.dim,
                            fontSize = 12.5.sp,
                            modifier = Modifier.padding(start = 12.dp),
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
        }
    }
}
