package com.trailmix.app.ui.transcript

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.ui.components.BackTitleBar
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.note.NoteDetailViewModel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    /** UX-19/UX-20: scroll to and highlight the transcript line with this label, if any. */
    highlightLabel: String? = null,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current
    var showFormatPicker by remember { mutableStateOf(false) }
    // UX-22: which line (if any) is being hand-corrected, and its in-progress draft text.
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editDraft by remember { mutableStateOf("") }

    /**
     * UX-23: the audio/video-free analog of a competitor "clip" — TrailMix never writes
     * captured audio to disk, so a flagged moment can only ever be shared as text. Reuses the
     * exact ACTION_SEND pattern [shareTranscript] already uses rather than inventing another.
     */
    fun shareExcerpt(line: TranscriptLine, noteTitle: String) {
        val text = "\"${line.text}\"\n\n— ${line.label}, $noteTitle"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, noteTitle)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share moment"))
    }

    fun shareTranscript(format: ExportFormat, lines: List<TranscriptLine>, title: String) {
        val text = if (format == ExportFormat.PLAIN_TEXT) {
            lines.joinToString("\n") { "${it.label}  ${it.text}" }
        } else {
            lines.joinToString("\n") { "**${it.label}** — ${it.text}" }
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share transcript"))
    }

    if (showFormatPicker) {
        val current = note
        if (current != null) {
            ExportFormatPickerDialog(
                initialFormat = defaultExportFormat,
                onConfirm = { format ->
                    showFormatPicker = false
                    shareTranscript(format, current.transcript, current.title)
                },
                onDismiss = { showFormatPicker = false },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackTitleBar(title = "Transcript", onBack = onBack, modifier = Modifier.weight(1f))
            val current = note
            if (current != null && current.transcript.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share transcript",
                    tint = c.dim,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .clickable { showFormatPicker = true },
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.border),
        )

        val lines = note?.transcript.orEmpty()
        if (lines.isEmpty()) {
            Text(
                text = "No transcript was captured for this note.",
                color = c.dim,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            val listState = rememberLazyListState()
            val highlightIndex = remember(lines, highlightLabel) {
                highlightLabel?.let { label -> lines.indexOfFirst { it.label == label } }?.takeIf { it >= 0 }
            }
            val flaggedIndices = remember(lines, note?.flaggedLabels) {
                note?.flaggedLabels.orEmpty().mapNotNull { flagLineIndex(lines, it) }.toSet()
            }
            // UX-19/UX-20: land the user on the moment their search actually matched,
            // not just somewhere in a possibly 90-minute transcript.
            LaunchedEffect(highlightIndex) {
                highlightIndex?.let { listState.animateScrollToItem(it) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(lines) { index, line ->
                    Row(verticalAlignment = Alignment.Top) {
                        // CAP-24: a flag marks the line that was being said when the user
                        // tapped — separate signal from the amber search-highlight below, so
                        // the two can coexist on the same line without fighting each other.
                        if (index in flaggedIndices) {
                            Icon(
                                painter = painterResource(R.drawable.ic_flag),
                                contentDescription = "Share this flagged moment",
                                tint = c.flag,
                                modifier = Modifier
                                    .padding(top = 3.dp, end = 6.dp)
                                    .height(16.dp)
                                    .clickable { note?.let { shareExcerpt(line, it.title) } },
                            )
                        }
                        if (index == editingIndex) {
                            // UX-22: correct an ASR mistake in place — styled exactly like
                            // NoteDetailScreen's existing whole-note edit mode.
                            Column(modifier = Modifier.weight(1f)) {
                                Row {
                                    line.speakerLabel?.let { speaker ->
                                        Text(
                                            text = speaker,
                                            color = c.amber,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp,
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                    }
                                    Text(
                                        text = line.label,
                                        color = c.text,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                    )
                                }
                                BasicTextField(
                                    value = editDraft,
                                    onValueChange = { editDraft = it },
                                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 23.8.sp),
                                    cursorBrush = SolidColor(c.amber),
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                )
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                ) {
                                    Text(
                                        text = "Cancel",
                                        color = c.dim,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.clickable { editingIndex = null }.padding(4.dp),
                                    )
                                    Text(
                                        text = "Save",
                                        color = c.amber,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable {
                                                viewModel.updateTranscriptLine(index, editDraft) {
                                                    editingIndex = null
                                                }
                                            }
                                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = buildAnnotatedString {
                                    // AI-01 (Falcon path): the label was landing in the data
                                    // model all session with nowhere to be seen — first real UI
                                    // consumer, additive (blank when diarization was never run).
                                    line.speakerLabel?.let { speaker ->
                                        withStyle(
                                            SpanStyle(color = c.amber, fontWeight = FontWeight.SemiBold),
                                        ) { append(speaker) }
                                        append("  ")
                                    }
                                    withStyle(
                                        SpanStyle(color = c.text, fontWeight = FontWeight.Bold),
                                    ) { append(line.label) }
                                    append(" — ")
                                    append(line.text)
                                },
                                color = c.dim,
                                fontSize = 14.sp,
                                lineHeight = 23.8.sp, // 1.7
                                modifier = (
                                    if (index == highlightIndex) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(c.amberTint)
                                            .padding(6.dp)
                                    } else {
                                        Modifier
                                    }
                                    ).clickable {
                                    editingIndex = index
                                    editDraft = line.text
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
