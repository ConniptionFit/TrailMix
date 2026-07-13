package com.trailmix.app.ui.recording

import android.Manifest
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.components.PrimaryPillButton
import com.trailmix.app.ui.theme.TrailMixAccent
import com.trailmix.app.ui.theme.TrailMixCard
import com.trailmix.app.ui.theme.TrailMixSecondaryText
import com.trailmix.app.ui.theme.TrailMixSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingScreen(
    onFinished: (Long) -> Unit,
    onDismiss: () -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var permissionAsked by remember { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(Unit) {
        val window = view.context.findActivityWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.startSession() else onDismiss()
    }

    LaunchedEffect(Unit) {
        if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(TrailMixSurface)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 18.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(TrailMixSecondaryText.copy(alpha = 0.45f))
                    .align(Alignment.CenterHorizontally),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "New note",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, enabled = !state.isProcessing) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = TrailMixSecondaryText,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(TrailMixCard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Today, ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrailMixSecondaryText,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            BasicTextField(
                value = state.typedNotes,
                onValueChange = viewModel::onTypedNotesChange,
                enabled = !state.isProcessing,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(TrailMixAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                decorationBox = { inner ->
                    Box {
                        if (state.typedNotes.isEmpty()) {
                            Text(
                                text = "Feel free to write notes here",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TrailMixSecondaryText,
                            )
                        }
                        inner()
                    }
                },
            )

            if (state.transcriptFinal.isNotBlank() || state.transcriptPartial.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = listOf(state.transcriptFinal, state.transcriptPartial)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TrailMixSecondaryText,
                    maxLines = 4,
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            AnimatedContent(
                targetState = state.isProcessing,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "recordingFooter",
            ) { processing ->
                if (processing) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = TrailMixAccent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = state.processingMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TrailMixSecondaryText,
                        )
                    }
                } else {
                    RecordingControls(
                        elapsedMs = state.elapsedMs,
                        isPaused = state.isPaused,
                        waveform = state.waveform,
                        onTogglePause = viewModel::togglePause,
                        onEnd = { viewModel.endSession(onFinished) },
                    )
                }
            }

            state.error?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::dismissError) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingControls(
    elapsedMs: Long,
    isPaused: Boolean,
    waveform: List<Float>,
    onTogglePause: () -> Unit,
    onEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(
            onClick = onTogglePause,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TrailMixCard),
        ) {
            Icon(
                imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (isPaused) "Resume" else "Pause",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = formatTimer(elapsedMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Waveform(levels = waveform, muted = isPaused)
        }

        PrimaryPillButton(text = "End", onClick = onEnd)
    }
}

@Composable
private fun Waveform(levels: List<Float>, muted: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(22.dp),
    ) {
        levels.take(12).forEach { level ->
            val animated by animateFloatAsState(
                targetValue = if (muted) 0.15f else level,
                animationSpec = tween(100),
                label = "wave",
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((6 + 16 * animated).dp)
                    .clip(CircleShape)
                    .background(TrailMixAccent.copy(alpha = if (muted) 0.35f else 1f)),
            )
        }
    }
}

private fun formatTimer(elapsedMs: Long): String {
    val totalSec = (elapsedMs / 1000).coerceAtLeast(0)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

private fun android.content.Context.findActivityWindow(): android.view.Window? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}
