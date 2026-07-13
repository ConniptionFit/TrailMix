package com.trailmix.app.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.components.BackTitleBar
import com.trailmix.app.ui.note.NoteDetailViewModel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val c = TrailMix.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        BackTitleBar(title = "Transcript", onBack = onBack)
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
