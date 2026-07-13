package com.trailmix.app.ui.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.ui.components.PrimaryPillButton
import com.trailmix.app.ui.theme.TrailMixCard
import com.trailmix.app.ui.theme.TrailMixSecondaryText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NotesScreen(
    onNewNote: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = TrailMixSecondaryText,
                    )
                }
            }

            Text(
                text = "My notes",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (notes.isEmpty()) {
                EmptyNotesState(modifier = Modifier.weight(1f).fillMaxWidth())
            } else {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelLarge,
                    color = TrailMixSecondaryText,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(note = note, onClick = { onOpenNote(note.id) })
                    }
                }
            }
        }

        PrimaryPillButton(
            text = "New note",
            icon = Icons.Outlined.Edit,
            onClick = onNewNote,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun EmptyNotesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
            Canvas(modifier = Modifier.size(72.dp)) {
                drawRoundRect(
                    color = Color(0xFF3A3A3A),
                    topLeft = Offset(size.width * 0.18f, size.height * 0.12f),
                    size = Size(size.width * 0.62f, size.height * 0.72f),
                    cornerRadius = CornerRadius(14f, 14f),
                )
                drawRoundRect(
                    color = Color(0xFF555555),
                    topLeft = Offset(size.width * 0.28f, size.height * 0.22f),
                    size = Size(size.width * 0.62f, size.height * 0.72f),
                    cornerRadius = CornerRadius(14f, 14f),
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No notes yet",
            style = MaterialTheme.typography.bodyLarge,
            color = TrailMixSecondaryText,
        )
    }
}

@Composable
private fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(note.createdAtEpochMs))
        .uppercase(Locale.getDefault())
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(note.createdAtEpochMs))
    val duration = formatDuration(note.durationMs)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrailMixCard, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color(0xFF161616), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.replace(" ", "\n"),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$time · $duration",
                style = MaterialTheme.typography.bodyMedium,
                color = TrailMixSecondaryText,
            )
        }
        Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = null,
            tint = TrailMixSecondaryText,
            modifier = Modifier
                .size(36.dp)
                .padding(6.dp),
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
