package com.trailmix.app.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
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
    onResume: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val current = note ?: return

    var editing by remember(current.id) { mutableStateOf(false) }
    var titleDraft by remember(current.id) { mutableStateOf("") }
    var bodyDraft by remember(current.id) { mutableStateOf("") }

    fun enterEdit() {
        titleDraft = current.title
        bodyDraft = current.displayBody
        editing = true
    }

    // While editing, Back cancels the edit rather than leaving the note.
    BackHandler(enabled = editing) { editing = false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        // Top bar: back / cancel on the left, actions on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editing) {
                Text(
                    text = "Cancel",
                    color = c.dim,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { editing = false }.padding(4.dp),
                )
                Text(
                    text = "Save",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { viewModel.saveEdits(titleDraft, bodyDraft) { editing = false } }
                        .padding(4.dp),
                )
            } else {
                BackChevron(onBack)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Resume",
                        color = c.amber,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .border(1.dp, c.amber, RoundedCornerShape(100.dp))
                            .clickable { onResume() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Edit",
                        color = c.dim,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(100.dp))
                            .border(1.dp, c.border, RoundedCornerShape(100.dp))
                            .clickable { enterEdit() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    // Provenance tinting only applies to an un-edited, merged body.
                    if (current.bodyOverride == null) {
                        Spacer(modifier = Modifier.size(8.dp))
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
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            if (editing) {
                BasicTextField(
                    value = titleDraft,
                    onValueChange = { titleDraft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(c.amber),
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.border))
                BasicTextField(
                    value = bodyDraft,
                    onValueChange = { bodyDraft = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 26.25.sp),
                    cursorBrush = SolidColor(c.amber),
                )
            } else {
                val meta = buildList {
                    add(relativeDay(current.createdAtEpochMs))
                    add(durationLabel(current.durationMs))
                    current.meetingTitle?.let { add(it) }
                    if (current.capturedInCall) add("in-call")
                    if (current.bodyOverride != null) add("edited")
                }.joinToString(" · ")
                Text(text = meta, color = c.dim, fontSize = 12.sp)
                Text(
                    text = current.title,
                    color = c.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )

                if (current.bodyOverride != null) {
                    // Hand-edited body: plain text, no provenance tinting.
                    Text(
                        text = current.bodyOverride!!,
                        color = c.text,
                        fontSize = 15.sp,
                        lineHeight = 26.25.sp,
                    )
                } else {
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
            }
        }

        // Bottom two-item nav row (hidden while editing)
        if (!editing) {
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
