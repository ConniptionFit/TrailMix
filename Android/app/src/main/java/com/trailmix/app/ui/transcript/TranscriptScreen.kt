package com.trailmix.app.ui.transcript

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.ui.components.BackTitleBar
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.note.NoteDetailViewModel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current
    var showFormatPicker by remember { mutableStateOf(false) }

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(color = c.text, fontWeight = FontWeight.Bold),
                            ) { append(line.label) }
                            append(" — ")
                            append(line.text)
                        },
                        color = c.dim,
                        fontSize = 14.sp,
                        lineHeight = 23.8.sp, // 1.7
                    )
                }
            }
        }
    }
}
