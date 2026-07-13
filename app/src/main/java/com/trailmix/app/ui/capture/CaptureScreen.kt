package com.trailmix.app.ui.capture

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun CaptureScreen(
    onMerged: (Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fragments by viewModel.fragments.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) viewModel.startRecording() else onCancel()
    }

    LaunchedEffect(Unit) {
        if (micGranted) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(enabled = !state.merging) {
        viewModel.cancel()
        onCancel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        // Recording status row
        Row(
            modifier = Modifier.padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (state.recording) c.recordingRed else c.dim),
            )
            Text(
                text = if (state.merging) "Merging on-device…" else "Recording · ${state.elapsedLabel}",
                color = c.dim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Text(
            text = "New note",
            color = c.text,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )

        // Live transcript preview card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.card)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = "LIVE TRANSCRIPT",
                color = c.dim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            )
            val liveLine = when {
                !state.speechAvailable ->
                    "On-device speech recognition isn't available on this device."
                state.livePartial.isNotBlank() -> "“…${state.livePartial}”"
                state.lastFinalLine.isNotBlank() -> "“…${state.lastFinalLine}”"
                else -> "Listening…"
            }
            Text(
                text = liveLine,
                color = c.dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp),
                maxLines = 2,
            )
        }

        Text(
            text = "YOUR NOTES",
            color = c.dim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )

        // Fragment textarea — plain editable text, 15sp, 1.7 line height
        BasicTextField(
            value = fragments,
            onValueChange = { viewModel.fragments.value = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            textStyle = TextStyle(
                color = c.text,
                fontSize = 15.sp,
                lineHeight = 25.5.sp,
            ),
            cursorBrush = SolidColor(c.amber),
            enabled = !state.merging,
        )

        // End & Merge button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .clip(RoundedCornerShape(14.dp))
                .background(c.recordingRed)
                .clickable(enabled = !state.merging) {
                    viewModel.endAndMerge(onMerged)
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.merging) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    text = "End & Merge",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
