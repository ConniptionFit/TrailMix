package com.trailmix.app.ui.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.components.PrimaryPillButton
import com.trailmix.app.ui.theme.TrailMixCard
import com.trailmix.app.ui.theme.TrailMixSecondaryText

@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val note = state.note

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (note?.obsidianRelativePath != null) {
                PrimaryPillButton(
                    text = "Open in Obsidian",
                    icon = Icons.Outlined.OpenInNew,
                    onClick = {
                        viewModel.openInObsidian()?.let { intent ->
                            runCatching {
                                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    },
                )
            }
        }

        if (note == null) {
            Text(
                text = "Note not found",
                style = MaterialTheme.typography.bodyLarge,
                color = TrailMixSecondaryText,
                modifier = Modifier.padding(24.dp),
            )
            return
        }

        Text(
            text = note.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.metaLine,
            style = MaterialTheme.typography.bodyMedium,
            color = TrailMixSecondaryText,
        )
        Spacer(modifier = Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Merged note", body = note.mergedMarkdown)
            if (note.summary.isNotBlank()) {
                SectionCard(title = "Summary", body = note.summary)
            }
            if (note.typedNotes.isNotBlank()) {
                SectionCard(title = "Typed notes", body = note.typedNotes)
            }
            if (note.transcript.isNotBlank()) {
                SectionCard(title = "Transcript", body = note.transcript)
            }
            if (note.obsidianRelativePath != null) {
                Text(
                    text = "Saved to Obsidian: ${note.obsidianRelativePath}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrailMixSecondaryText,
                )
            } else {
                Text(
                    text = "Not exported to Obsidian. Link a vault in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrailMixSecondaryText,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrailMixCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = TrailMixSecondaryText,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
