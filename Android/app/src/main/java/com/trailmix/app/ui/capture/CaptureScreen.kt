package com.trailmix.app.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.speech.EngineKind
import com.trailmix.app.ui.components.TmCloseButton
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmInfoDialog
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.components.showUndo
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    onMerged: (Long) -> Unit,
    onCancel: () -> Unit,
    /** Navigate to Home without touching the recording (CAP-10) — it keeps running. */
    onMinimize: () -> Unit = onCancel,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val mergeStatus by viewModel.mergeStatus.collectAsStateWithLifecycle()
    val rollingSummary by viewModel.rollingSummary.collectAsStateWithLifecycle()
    val fragments by viewModel.fragments.collectAsStateWithLifecycle()
    val liveLines by viewModel.liveLines.collectAsStateWithLifecycle()
    val pendingRecovery by viewModel.pendingRecovery.collectAsStateWithLifecycle()
    val prePermissionShown by viewModel.prePermissionShown.collectAsStateWithLifecycle()
    val templateOptions by viewModel.templateOptions.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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

    // ── Permissions (C5) ────────────────────────────────────────────────────────────────
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var micDenied by remember { mutableStateOf(false) }

    // CAP-12: the persistent capture notification (timer, pause/stop actions) needs
    // POST_NOTIFICATIONS on Android 13+. Best-effort: a denial doesn't block capture.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        micDenied = !granted
        if (granted) {
            viewModel.startRecording()
            requestNotificationsIfNeeded()
        }
    }

    // First run: explain the two permissions before the system asks. Afterwards ask directly.
    // A denial now lands on an explanation (below) instead of silently leaving the screen.
    var introVisible by remember { mutableStateOf(false) }
    LaunchedEffect(prePermissionShown) {
        if (micGranted) {
            requestNotificationsIfNeeded()
            viewModel.startRecording()
        } else when (prePermissionShown) {
            null -> Unit
            false -> introVisible = true
            true -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── Dialog and sheet state ──────────────────────────────────────────────────────────
    var transcriptSheet by remember { mutableStateOf(false) }
    var optionsSheet by remember { mutableStateOf(false) }
    var templateSheet by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf(viewModel.currentTemplate) }

    // C4: both the arrow and the system Back gesture minimise; neither can lose a recording.
    // Discard lives in Recording options. A screen that never started (denied, intro) just leaves.
    BackHandler(enabled = !transcriptSheet && !optionsSheet && !templateSheet) {
        if (state.recording || state.paused || state.merging) {
            onMinimize()
        } else {
            viewModel.cancel()
            onCancel()
        }
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

    // Device audio is opt-in from Recording options. When the user asks for it, fire the system
    // consent, with a one-time explainer first (Android's dialog talks about the screen).
    LaunchedEffect(state.deviceAudioPrompt) {
        if (state.deviceAudioPrompt == DeviceAudioPrompt.ASK) {
            viewModel.consumeDeviceAudioPrompt()
            launchProjectionConsent()
        }
    }
    if (state.deviceAudioPrompt == DeviceAudioPrompt.EXPLAIN_THEN_ASK) {
        TmConfirmDialog(
            title = stringResource(R.string.capture_projection_title),
            body = stringResource(R.string.capture_projection_body),
            confirmLabel = stringResource(R.string.capture_projection_continue),
            dismissLabel = stringResource(R.string.capture_projection_not_now),
            onConfirm = {
                viewModel.markExplainerShown()
                viewModel.consumeDeviceAudioPrompt()
                launchProjectionConsent()
            },
            onDismiss = {
                viewModel.markExplainerShown()
                viewModel.consumeDeviceAudioPrompt()
            },
        )
    }

    // CAP-12: the call this capture started during just ended.
    if (state.callEndedPrompt) {
        TmConfirmDialog(
            title = stringResource(R.string.capture_call_ended_title),
            body = stringResource(R.string.capture_call_ended_body),
            confirmLabel = stringResource(R.string.capture_call_ended_now),
            dismissLabel = stringResource(R.string.capture_call_ended_later),
            onConfirm = {
                viewModel.consumeCallEndedPrompt()
                viewModel.endAndMerge(onMerged)
            },
            onDismiss = { viewModel.consumeCallEndedPrompt() },
        )
    }

    if (confirmDiscard) {
        TmConfirmDialog(
            title = stringResource(
                if (state.paused) R.string.capture_discard_title_paused else R.string.capture_discard_title_recording,
            ),
            body = stringResource(R.string.capture_discard_body),
            confirmLabel = stringResource(R.string.capture_discard_confirm),
            dismissLabel = stringResource(R.string.capture_discard_keep),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                viewModel.cancel()
                onCancel()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
    if (helpDialog) {
        TmInfoDialog(
            title = stringResource(R.string.capture_hear_title),
            body = stringResource(R.string.capture_hear_body),
            dismissLabel = stringResource(R.string.action_got_it),
            onDismiss = { helpDialog = false },
        )
    }

    // ── Typed notes (C2) ────────────────────────────────────────────────────────────────
    var notes by remember { mutableStateOf(TextFieldValue(fragments)) }
    LaunchedEffect(fragments) {
        // The session can replace the buffer from outside (resuming into a note, recovery).
        if (fragments != notes.text) notes = TextFieldValue(fragments, TextRange(fragments.length))
    }
    val applyEdit = { edit: NoteShortcuts.Edit ->
        notes = TextFieldValue(edit.text, TextRange(edit.cursor))
        viewModel.fragments.value = edit.text
    }

    // ── Silence hint (C1): 10 s without anything audible ────────────────────────────────
    val levelAvailable = state.engineKind == EngineKind.MLKIT
    var lastHeardMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var showSilenceHint by remember { mutableStateOf(false) }
    LaunchedEffect(level) {
        if (level >= SilenceHint.AUDIBLE_LEVEL) {
            lastHeardMs = SystemClock.elapsedRealtime()
            showSilenceHint = false
        }
    }
    LaunchedEffect(state.recording, levelAvailable) {
        lastHeardMs = SystemClock.elapsedRealtime()
        while (state.recording && levelAvailable) {
            showSilenceHint = SilenceHint.shouldShow(SystemClock.elapsedRealtime(), lastHeardMs, state.recording, levelAvailable)
            delay(1_000)
        }
        showSilenceHint = false
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────
    val flaggedText = stringResource(R.string.capture_flagged, "%s")
    val undoLabel = stringResource(R.string.action_undo)
    val onFlag = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val label = viewModel.flagMoment()
        if (label != null) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val undone = snackbarHostState.showUndo(flaggedText.replace("%s", label), undoLabel)
                if (undone) viewModel.unflagMoment(label)
            }
        }
        Unit
    }
    val onPause = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.pause()
    }
    val onResume = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.resume()
    }
    val onEnd = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.endAndMerge(onMerged)
    }
    val failure = captureFailureOf(state.captureError, state.speechAvailable)
    val noTranscript = liveLines.isEmpty() && failure != null
    val imeVisible = WindowInsets.isImeVisible

    // ── Layout ──────────────────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            when {
                introVisible -> {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TmTopBar(
                            title = "",
                            navigation = { TmCloseButton(onClick = onCancel) },
                        )
                        PermissionIntro(
                            onContinue = {
                                introVisible = false
                                viewModel.markPrePermissionShown()
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            onClose = onCancel,
                        )
                    }
                }

                micDenied && !micGranted -> {
                    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        TmTopBar(
                            title = state.noteTitle,
                            navigation = { TmCloseButton(onClick = onCancel) },
                        )
                        CaptureFailureBanner(
                            failure = null,
                            micDenied = true,
                            onRetry = {},
                            onOpenSettings = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.fromParts("package", context.packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            modifier = Modifier.padding(horizontal = TmSpacing.l),
                        )
                    }
                }

                else -> {
                    if (imeVisible && !state.merging) {
                        KeyboardHeader(
                            state = state,
                            level = level,
                            onMinimise = onMinimize,
                            onPause = onPause,
                            onResume = onResume,
                            onFlag = onFlag,
                        )
                    } else {
                        TmTopBar(
                            title = state.noteTitle,
                            subtitle = state.meetingTitle?.let { stringResource(R.string.capture_from_calendar, it) },
                            navigation = { MinimiseButton(onMinimize) },
                            actions = { CaptureTopBarActions(onOptions = { optionsSheet = true }) },
                        )
                    }

                    // UX-25: the body scrolls as one unit; the dock below is a fixed sibling so
                    // End & merge can never be pushed off-screen however long the session runs.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = TmSpacing.l),
                        verticalArrangement = Arrangement.spacedBy(TmSpacing.m),
                    ) {
                        if (state.merging) {
                            MergingPanel(
                                state = state,
                                mergeStatus = mergeStatus,
                                transcriptLines = liveLines.size,
                                typedLines = notes.text.lines().count { it.isNotBlank() },
                                onBackToHome = onMinimize,
                            )
                        } else {
                            if (!imeVisible) {
                                CaptureStatusZone(
                                    state = state,
                                    level = level,
                                    showSilenceHint = showSilenceHint,
                                )
                            }
                            CaptureFailureBanner(
                                failure = failure,
                                micDenied = false,
                                onRetry = viewModel::retryListening,
                                onOpenSettings = {},
                            )
                            if (state.paused) {
                                PausedPanel()
                            } else {
                                LiveTranscriptCard(
                                    lines = liveLines,
                                    partial = state.livePartial,
                                    ticker = imeVisible,
                                    onExpand = { transcriptSheet = true },
                                )
                            }
                            rollingSummary?.let { if (!imeVisible) SoFarCard(it) }
                            Text(
                                stringResource(R.string.capture_notes_header),
                                style = TrailMix.type.overline,
                                color = c.dim,
                                modifier = Modifier.padding(top = TmSpacing.s),
                            )
                            NotesField(
                                value = notes,
                                onValueChange = { v ->
                                    notes = v
                                    viewModel.fragments.value = v.text
                                },
                                enabled = !state.merging,
                            )
                        }
                    }

                    if (!state.merging) {
                        ShortcutChips(
                            onHeading = { applyEdit(NoteShortcuts.heading(notes.text, notes.selection.start)) },
                            onQuestion = { applyEdit(NoteShortcuts.question(notes.text, notes.selection.start)) },
                        )
                        if (!imeVisible) {
                            CaptureDock(
                                paused = state.paused,
                                flagCount = state.flagCount,
                                noTranscript = noTranscript,
                                onPause = onPause,
                                onResume = onResume,
                                onFlag = onFlag,
                                onEnd = onEnd,
                                modifier = Modifier.navigationBarsPadding(),
                            )
                        }
                    }
                }
            }
        }

        TmSnackbarHost(
            state = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp),
        )
    }

    if (transcriptSheet) {
        TranscriptSheet(
            lines = liveLines,
            partial = state.livePartial,
            state = state,
            level = level,
            onDismiss = { transcriptSheet = false },
            dock = {
                CaptureDock(
                    paused = state.paused,
                    flagCount = state.flagCount,
                    noTranscript = noTranscript,
                    onPause = onPause,
                    onResume = onResume,
                    onFlag = onFlag,
                    onEnd = {
                        transcriptSheet = false
                        onEnd()
                    },
                )
            },
        )
    }

    if (optionsSheet) {
        RecordingOptionsSheet(
            state = state,
            deviceAudioSupported = viewModel.deviceAudioSupported,
            templateLabel = templateOptions.firstOrNull { it.stored == selectedTemplate }?.label ?: selectedTemplate,
            onSelectInput = viewModel::selectInput,
            onTogglePhoneAudio = { enable ->
                if (enable) viewModel.requestDeviceAudio() else viewModel.disableDeviceAudio()
                optionsSheet = false
            },
            onTemplate = {
                optionsSheet = false
                templateSheet = true
            },
            onHelp = {
                optionsSheet = false
                helpDialog = true
            },
            onOutput = {
                optionsSheet = false
                // Output routing is a system function; the volume panel is the closest surface
                // a third-party app may open.
                runCatching {
                    context.startActivity(
                        Intent(Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onDiscard = {
                optionsSheet = false
                confirmDiscard = true
            },
            onDismiss = { optionsSheet = false },
        )
    }

    if (templateSheet) {
        TemplateSheet(
            options = templateOptions,
            selected = selectedTemplate,
            onSelect = {
                selectedTemplate = it
                viewModel.setTemplate(it)
                templateSheet = false
            },
            onDismiss = { templateSheet = false },
        )
    }
}
