package com.trailmix.app.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.ui.components.BackChevron
import com.trailmix.app.ui.theme.TrailMix
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    onOpenTranscript: () -> Unit,
    onOpenChat: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val current = note ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        // Top bar: back chevron + sources pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(onBack)
            Text(
                text = if (current.showSources) "Sources shown" else "Show sources",
                color = c.dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .border(1.dp, c.border, RoundedCornerShape(100.dp))
                    .clickable { viewModel.toggleShowSources() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            val meta = buildList {
                add(relativeDay(current.createdAtEpochMs))
                add(durationLabel(current.durationMs))
                current.meetingTitle?.let { add(it) }
                if (current.capturedInCall) add("in-call")
            }.joinToString(" · ")
            Text(
                text = meta,
                color = c.dim,
                fontSize = 12.sp,
            )
            Text(
                text = current.title,
                color = c.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            // Note body with provenance tinting
            val body = buildAnnotatedString {
                current.segments.forEachIndexed { i, segment ->
                    if (i > 0) append(" ")
                    if (current.showSources) {
                        withStyle(
                            SpanStyle(
                                background = when (segment.source) {
                                    Provenance.FRAGMENT -> c.amberTint
                                    Provenance.TRANSCRIPT -> c.tealTint
                                },
                                // Fixed dark text on pale tints in BOTH modes (design requirement)
                                color = c.spanText,
                            ),
                        ) { append(segment.text) }
                    } else {
                        append(segment.text)
                    }
                }
            }
            Text(
                text = body,
                color = c.text,
                fontSize = 15.sp,
                lineHeight = 26.25.sp, // 1.75
            )
        }

        // Bottom two-item nav row
        Column(modifier = Modifier.navigationBarsPadding()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.border),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Transcript",
                    color = c.dim,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenTranscript)
                        .padding(vertical = 14.dp),
                )
                Text(
                    text = "Chat & Recipes",
                    color = c.amber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenChat)
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
}

private fun relativeDay(epochMs: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = epochMs
    val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when {
        that == today -> "Today"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

private fun durationLabel(durationMs: Long): String {
    val min = (durationMs / 60_000).coerceAtLeast(0)
    return if (min < 1) "<1 min" else "$min min"
}
