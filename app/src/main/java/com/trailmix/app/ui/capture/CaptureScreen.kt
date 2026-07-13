package com.trailmix.app.ui.capture

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
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

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            viewModel.onProjectionGranted(result.resultCode, data)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        // Recording status row + audio menu (3 dots, upper right)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
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
            if (state.deviceAudioActive) {
                Text(
                    text = "· device audio",
                    color = c.teal,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            CaptureMenu(
                state = state,
                deviceAudioSupported = viewModel.deviceAudioSupported,
                onSelectInput = viewModel::selectInput,
                onEnableDeviceAudio = {
                    val mpm = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                onDisableDeviceAudio = viewModel::disableDeviceAudio,
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

/**
 * The in-capture audio menu (3 dots, upper right): pick the input mic, toggle
 * device-audio capture, or jump to the system output panel. Kept deliberately
 * small — one glance, one tap.
 */
@Composable
private fun CaptureMenu(
    state: CaptureUiState,
    deviceAudioSupported: Boolean,
    onSelectInput: (Int) -> Unit,
    onEnableDeviceAudio: () -> Unit,
    onDisableDeviceAudio: () -> Unit,
) {
    val c = TrailMix.colors
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Audio settings",
                tint = c.dim,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            Text(
                text = "MICROPHONE",
                color = c.dim,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            state.inputOptions.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option.label, fontSize = 14.sp, color = c.text) },
                    leadingIcon = {
                        RadioButton(
                            selected = index == state.selectedInputIndex,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = c.amber),
                        )
                    },
                    onClick = { onSelectInput(index) },
                )
            }
            HorizontalDivider(color = c.border)
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Capture device audio", fontSize = 14.sp, color = c.text)
                        Text(
                            text = if (deviceAudioSupported) {
                                "Videos & media from other apps"
                            } else {
                                "Needs the on-device recognizer model"
                            },
                            fontSize = 11.sp,
                            color = c.dim,
                        )
                    }
                },
                trailingIcon = {
                    if (state.deviceAudioActive) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = c.teal)
                    }
                },
                enabled = deviceAudioSupported,
                onClick = {
                    if (state.deviceAudioActive) onDisableDeviceAudio() else onEnableDeviceAudio()
                    open = false
                },
            )
            HorizontalDivider(color = c.border)
            DropdownMenuItem(
                text = { Text("Output device…", fontSize = 14.sp, color = c.text) },
                onClick = {
                    open = false
                    // Output routing is a system function; the volume panel is
                    // the closest surface a third-party app may open.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.Panel.ACTION_VOLUME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            Text(
                text = when (state.engineKind) {
                    com.trailmix.app.data.speech.EngineKind.MLKIT -> "Recognizer: Gemini on-device"
                    com.trailmix.app.data.speech.EngineKind.LEGACY -> "Recognizer: system (mic only)"
                    com.trailmix.app.data.speech.EngineKind.NONE -> "No speech recognizer available"
                },
                color = c.dim,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}
