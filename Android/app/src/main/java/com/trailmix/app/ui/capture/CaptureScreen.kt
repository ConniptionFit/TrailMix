package com.trailmix.app.ui.capture

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.delay

@Composable
fun CaptureScreen(
    onMerged: (Long) -> Unit,
    onCancel: () -> Unit,
    /** Navigate to Home without touching the recording (CAP-10) — it keeps running. */
    onMinimize: () -> Unit = onCancel,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mergeStatus by viewModel.mergeStatus.collectAsStateWithLifecycle()
    val rollingSummary by viewModel.rollingSummary.collectAsStateWithLifecycle()
    val fragments by viewModel.fragments.collectAsStateWithLifecycle()
    val pendingRecovery by viewModel.pendingRecovery.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current

    // REL-19: found live during a long soak test — a session can be abandoned mid-flight
    // with the process itself still alive (most plausibly Android destroying this
    // backgrounded Activity under memory pressure while the foreground service keeps the
    // process up), and Navigation's saved back stack then restores straight onto this route.
    // beginSession() now refuses to start over an unrecovered journal instead of silently
    // replacing it — this bounces back to Home so its "recover this?" prompt is what the
    // user actually sees, rather than a screen that never started listening with no
    // explanation why.
    LaunchedEffect(pendingRecovery) {
        if (pendingRecovery != null && !state.recording && !state.paused && !state.merging) {
            onMinimize()
        }
    }

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

    // CAP-12: the persistent capture notification (timer, pause/stop actions) needs
    // POST_NOTIFICATIONS on Android 13+, or the foreground service runs with nothing
    // visible — silently, which is exactly the "forgotten in the background" problem
    // this feature exists to fix. Best-effort: a denial doesn't block starting the
    // capture (mic access is the only hard requirement), it just means no notification.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (micGranted) {
            viewModel.startRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Live-transcript expand state is hoisted here so Back can collapse it
    // without ever touching the recording.
    var transcriptExpanded by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Back while the transcript is expanded just collapses it — the recording
    // keeps running (it only stops on End & Merge or an explicit Discard).
    BackHandler(enabled = transcriptExpanded) { transcriptExpanded = false }
    // Otherwise Back asks before discarding an in-progress recording. A paused session
    // still has an un-saved transcript sitting in memory, so it confirms too (CAP-12).
    BackHandler(enabled = !state.merging && !transcriptExpanded) {
        if (state.recording || state.paused) confirmDiscard = true else { viewModel.cancel(); onCancel() }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = c.card,
            title = { Text("Discard this recording?", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    "You're still recording. Discarding throws away the transcript so far. " +
                        "To keep it, tap End & Merge instead.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Discard",
                    color = c.recordingRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            confirmDiscard = false
                            viewModel.cancel()
                            onCancel()
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Keep recording",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { confirmDiscard = false }
                        .padding(8.dp),
                )
            },
        )
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            viewModel.onProjectionGranted(result.resultCode, data)
        }
    }
    val launchProjectionConsent = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // Device audio is opt-in from the in-call menu (capture is mic-only by
    // default). When the user asks for it, fire the system consent — with a
    // one-time explainer first (Android's dialog talks about the screen;
    // TrailMix only takes the audio stream from it).
    LaunchedEffect(state.deviceAudioPrompt) {
        if (state.deviceAudioPrompt == DeviceAudioPrompt.ASK) {
            viewModel.consumeDeviceAudioPrompt()
            launchProjectionConsent()
        }
    }
    if (state.deviceAudioPrompt == DeviceAudioPrompt.EXPLAIN_THEN_ASK) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeDeviceAudioPrompt() },
            containerColor = c.card,
            title = { Text("Hear what your phone plays", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    "To transcribe meetings and videos, TrailMix can listen to the audio " +
                        "other apps play.\n\nAndroid only grants that through its screen-share " +
                        "permission, so the next dialog will mention your screen — but TrailMix " +
                        "only receives the audio stream. It never records, stores, or even sees " +
                        "your screen.\n\nNote: Android blocks every app from hearing protected " +
                        "voice-call audio, and some apps opt out of capture entirely.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Continue",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            viewModel.markExplainerShown()
                            viewModel.consumeDeviceAudioPrompt()
                            launchProjectionConsent()
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Not now",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable {
                            viewModel.markExplainerShown()
                            viewModel.consumeDeviceAudioPrompt()
                        }
                        .padding(8.dp),
                )
            },
        )
    }

    // CAP-12: the call this capture started during just ended (AudioManager left
    // call/communication mode). A matching notification fires from CaptureService in
    // case the app isn't foregrounded when this happens — this dialog is the in-app
    // echo of the same one-shot event.
    if (state.callEndedPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeCallEndedPrompt() },
            containerColor = c.card,
            title = { Text("Call ended", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    "The call this note was tracking just ended. Finish and save it now, " +
                        "or keep it running — you can pause and come back to it later.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Finish now",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable {
                            viewModel.consumeCallEndedPrompt()
                            viewModel.endAndMerge(onMerged)
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    text = "Finish later",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { viewModel.consumeCallEndedPrompt() }
                        .padding(8.dp),
                )
            },
        )
    }

    // CAP-24: brief on-screen echo of a flag tap — clears itself, no dismiss needed.
    var flaggedConfirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flaggedConfirmation) {
        if (flaggedConfirmation != null) {
            delay(2_000)
            flaggedConfirmation = null
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
        // Recording status row: back chevron upper-LEFT (UX-07, standard Android
        // convention — was next to the 3-dot at top-right), audio menu (3 dots) upper right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Minimize — a literal chevron-back affordance, goes to Home while the
            // recording keeps running in the background (CAP-10). Distinct from system
            // Back, which still confirms discard.
            IconButton(onClick = onMinimize) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Home (recording continues)",
                    tint = c.dim,
                )
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            state.recording -> c.recordingRed
                            state.paused -> c.amber
                            else -> c.dim
                        },
                    ),
            )
            Text(
                text = when {
                    // REL-10: the same label the foreground notification is showing, chunk
                    // count and all — a long merge must not look identical to a stuck one.
                    state.merging -> mergeStatus?.label() ?: MergeStatus.MERGING
                    state.paused -> "Paused · ${state.elapsedLabel}"
                    else -> "Recording · ${state.elapsedLabel}"
                },
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
            flaggedConfirmation?.let { label ->
                Text(
                    text = "· Flagged $label",
                    color = c.flag,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // CAP-24: tap-to-flag an important moment — same gating as Pause/Resume, since
            // there's nothing meaningful to flag before a session starts or after it merges.
            if (state.recording || state.paused) {
                IconButton(onClick = { flaggedConfirmation = viewModel.flagMoment() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_flag),
                        contentDescription = "Flag this moment",
                        tint = c.dim,
                    )
                }
            }
            // CAP-12: same pause/resume the notification's actions drive — releases the
            // mic without ending the session. Hidden while merging (nothing to pause).
            if (state.recording || state.paused) {
                IconButton(onClick = { if (state.paused) viewModel.resume() else viewModel.pause() }) {
                    // PlayArrow is in material-icons-core; Pause is not (REL-02), so the
                    // pause glyph comes from a local drawable instead.
                    if (state.paused) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Resume recording",
                            tint = c.dim,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_pause),
                            contentDescription = "Pause recording",
                            tint = c.dim,
                        )
                    }
                }
            }
            CaptureMenu(
                state = state,
                deviceAudioSupported = viewModel.deviceAudioSupported,
                onSelectInput = viewModel::selectInput,
                onEnableDeviceAudio = viewModel::requestDeviceAudio,
                onDisableDeviceAudio = viewModel::disableDeviceAudio,
            )
        }

        Text(
            // UX-15: show the note being recorded INTO, not the calendar event. This read
            // `meetingTitle ?: "New note"`, so resuming into an existing note (CAP-07)
            // displayed "New note" on screen while the CAP-13 notification — which already
            // reads noteTitle — correctly showed the note's real name. noteTitle already
            // encodes the same fallback chain (note title → meeting title → "New note"),
            // so this is strictly more informative, never less.
            text = state.noteTitle,
            color = c.text,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (state.meetingTitle != null) {
            Text(
                text = "Capturing during this meeting",
                color = c.dim,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // UX-25: the transcript/summary/notes stack scrolls as one unit. The "so far"
        // card and live transcript have no bound on how tall they grow over a long
        // capture, and this section used to sit in the same non-scrolling Column as the
        // template chips and End & Merge button below it — past several minutes those
        // got pushed off the bottom of the screen with no way to reach them, including
        // no way to end the capture at all. weight(1f) + verticalScroll keeps the header
        // above and the chips/End & Merge button below always on-screen, however long
        // the session runs.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Live transcript card — tap to expand into the recent transcript
            val liveLines by viewModel.liveLines.collectAsStateWithLifecycle()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.card)
                    .clickable { transcriptExpanded = !transcriptExpanded }
                    .animateContentSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LIVE TRANSCRIPT",
                        color = c.dim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (transcriptExpanded) "collapse" else "tap to expand",
                        color = c.dim,
                        fontSize = 10.sp,
                    )
                }
                if (transcriptExpanded) {
                    val listState = rememberLazyListState()
                    LaunchedEffect(liveLines.size, state.livePartial) {
                        val last = liveLines.size + (if (state.livePartial.isNotBlank()) 1 else 0) - 1
                        if (last >= 0) listState.animateScrollToItem(last)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .padding(top = 8.dp),
                    ) {
                        if (liveLines.isEmpty() && state.livePartial.isBlank()) {
                            item {
                                Text(
                                    text = "Nothing transcribed yet.",
                                    color = c.dim,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        items(liveLines.size) { i ->
                            val line = liveLines[i]
                            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                                Text(
                                    text = line.label,
                                    color = c.teal,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(end = 8.dp, top = 1.dp),
                                )
                                Text(
                                    text = line.text,
                                    color = c.text,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                )
                            }
                        }
                        if (state.livePartial.isNotBlank()) {
                            item {
                                Text(
                                    text = "…${state.livePartial}",
                                    color = c.dim,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp,
                                )
                            }
                        }
                    }
                } else {
                    // Bound to a local: `state` is a delegated property, so it can't smart-cast.
                    val captureError = state.captureError
                    val liveLine = when {
                        // REL-11: a situational failure (mic held by a call or another app) is
                        // reported as itself. It is checked first because the generic
                        // "not available on this device" below would be actively misleading —
                        // the device is fine, and retrying in a minute will work.
                        captureError != null -> captureError
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
                if (state.deviceAudioActive && state.deviceAudioSilent) {
                    Text(
                        text = "No device audio detected — the playing app may not allow " +
                            "capture (calls never do). The mic is still listening.",
                        color = c.amber,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // AI-11: the periodically-refreshed "so far" condensation — absent until the first
            // pass finishes, and never shown as an empty/loading card in the meantime, since most
            // short captures will end before it ever has anything to say.
            rollingSummary?.let { summary ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.card)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "SO FAR",
                        color = c.dim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    )
                    Text(
                        text = summary,
                        color = c.text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
                    .heightIn(min = 120.dp),
                textStyle = TextStyle(
                    color = c.text,
                    fontSize = 15.sp,
                    lineHeight = 25.5.sp,
                ),
                cursorBrush = SolidColor(c.amber),
                enabled = !state.merging,
            )
        }

        // Summary template selector (UX-02) — steers the structured-summary prompt.
        // AI-03: chips list the built-ins plus the user's custom templates from Settings.
        val templateOptions by viewModel.templateOptions.collectAsStateWithLifecycle()
        var selectedTemplate by remember { mutableStateOf(viewModel.currentTemplate) }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(templateOptions.size) { i ->
                val option = templateOptions[i]
                val selected = option.stored == selectedTemplate
                Text(
                    text = option.label,
                    color = if (selected) Color.White else c.dim,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.amber else c.card)
                        .clickable {
                            selectedTemplate = option.stored
                            viewModel.setTemplate(option.stored)
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

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
    var showCaptureHelp by remember { mutableStateOf(false) }

    // CAP-06: capturable-source guidance — honest, dense, mirrors the v1.2.0 finding.
    // (Up-front detection of an app's FLAG_NO_MEDIA_PROJECTION opt-out isn't feasible from
    // a third-party app: AudioAttributes.getFlags() masks hidden flags, so the ≥5 s
    // silence hint in the capture card remains the runtime signal.)
    if (showCaptureHelp) {
        AlertDialog(
            onDismissRequest = { showCaptureHelp = false },
            containerColor = c.card,
            title = { Text("What can be captured?", color = c.text, fontSize = 17.sp) },
            text = {
                Text(
                    "Microphone — always works: in-person conversation, speakerphone, " +
                        "anything the mic can hear.\n\n" +
                        "System audio (opt-in from this menu) — works for most apps: " +
                        "browsers, podcasts, games, most video players.\n\n" +
                        "What can't be captured:\n" +
                        "• YouTube and DRM/streaming apps — they opt out of capture, and " +
                        "Android enforces it.\n" +
                        "• The other side of phone and VoIP calls — Android never shares " +
                        "call audio with any app.\n\n" +
                        "For those, put the call or video on speakerphone and let the mic " +
                        "pick it up.\n\n" +
                        "TrailMix can't tell up front whether an app allows capture — if " +
                        "system audio stays silent, a hint appears in the capture card.",
                    color = c.dim,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            },
            confirmButton = {
                Text(
                    text = "Got it",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { showCaptureHelp = false }
                        .padding(8.dp),
                )
            },
        )
    }

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
                        Text("Capture system audio", fontSize = 14.sp, color = c.text)
                        Text(
                            text = if (deviceAudioSupported) {
                                "Audio only — your screen is never recorded"
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
                text = { Text("What can be captured?", fontSize = 14.sp, color = c.text) },
                onClick = {
                    open = false
                    showCaptureHelp = true
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
