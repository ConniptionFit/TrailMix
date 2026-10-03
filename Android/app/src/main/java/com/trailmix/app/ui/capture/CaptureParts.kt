package com.trailmix.app.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.model.TemplateOption
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.data.speech.EngineKind
import com.trailmix.app.data.speech.MergeStage
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.ui.components.RecordingState
import com.trailmix.app.ui.components.TmAssistChip
import com.trailmix.app.ui.components.TmButton
import com.trailmix.app.ui.components.TmButtonIcon
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmLongTaskProgress
import com.trailmix.app.ui.components.TmOutlinedButton
import com.trailmix.app.ui.components.TmRecordingIndicator
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmSheetAction
import com.trailmix.app.ui.components.TmStepLine
import com.trailmix.app.ui.components.TmSwitchRow
import com.trailmix.app.ui.components.TmTonalButton
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.launch

/** The in-progress state of the recording indicator for a given session state. */
internal fun CaptureUiState.indicatorState(): RecordingState = when {
    paused -> RecordingState.PAUSED
    captureFailureOf(captureError, speechAvailable) != null -> RecordingState.UNAVAILABLE
    else -> RecordingState.RECORDING
}

@Composable
internal fun CaptureTopBarActions(onOptions: () -> Unit) {
    TmIconButton(Icons.Filled.MoreVert, stringResource(R.string.action_more_options), onOptions)
}

@Composable
internal fun MinimiseButton(onClick: () -> Unit) {
    TmIconButton(Icons.Filled.KeyboardArrowDown, stringResource(R.string.capture_minimise), onClick)
}

/** Status pill + source chip + hints, shown under the top bar (C2/C3). */
@Composable
internal fun CaptureStatusZone(
    state: CaptureUiState,
    level: Float,
    showSilenceHint: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TmSpacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TmSpacing.s)) {
            TmRecordingIndicator(
                state = state.indicatorState(),
                elapsedLabel = state.elapsedLabel,
                level = level,
                stateLabel = if (state.paused) stringResource(R.string.capture_state_paused) else null,
            )
            if (!state.paused) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .border(1.dp, c.border, CircleShape)
                        .padding(horizontal = TmSpacing.m, vertical = TmSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TmSpacing.xs),
                ) {
                    if (state.deviceAudioActive) TmIcon(TmIcons.Devices, null, size = 16.dp, tint = c.dim)
                    Text(
                        text = stringResource(
                            if (state.deviceAudioActive) R.string.capture_status_mic_and_phone else R.string.capture_status_mic,
                        ),
                        style = TrailMix.type.caption,
                        color = c.dim,
                    )
                }
            }
        }
        if (showSilenceHint) {
            Text(stringResource(R.string.capture_silence_hint), style = TrailMix.type.bodySmall, color = c.dim)
        }
        if (state.deviceAudioActive && state.deviceAudioSilent) {
            Text(stringResource(R.string.capture_device_audio_silent), style = TrailMix.type.bodySmall, color = c.dim)
        }
    }
}

/** C4: the shared failure banner; only the words and the actions change. */
@Composable
internal fun CaptureFailureBanner(
    failure: CaptureFailure?,
    micDenied: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    if (failure == null && !micDenied) return
    val (title, body) = when {
        micDenied -> R.string.capture_error_denied_title to R.string.capture_error_denied_body
        failure == CaptureFailure.MIC_BUSY -> R.string.capture_error_mic_title to R.string.capture_error_mic_body
        failure == CaptureFailure.RECOGNIZER_STOPPED -> R.string.capture_error_recognizer_title to R.string.capture_error_recognizer_body
        else -> R.string.capture_error_no_recognizer_title to R.string.capture_error_no_recognizer_body
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.errorContainer)
            .padding(TmSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.m), verticalAlignment = Alignment.Top) {
            TmIcon(TmIcons.MicOff, null, tint = c.recordingRed)
            Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
                Text(stringResource(title), style = TrailMix.type.heading, color = c.text)
                Text(stringResource(body), style = TrailMix.type.bodySmall, color = c.text)
            }
        }
        when {
            micDenied -> TmTonalButton(stringResource(R.string.capture_open_android_settings), onClick = onOpenSettings)
            failure == CaptureFailure.NO_RECOGNIZER -> Unit
            else -> TmTonalButton(stringResource(R.string.action_try_again), onClick = onRetry)
        }
    }
}

@Composable
internal fun PausedPanel(modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(TmSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        TmIcon(TmIcons.Pause, null, tint = c.dim)
        Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
            Text(stringResource(R.string.capture_paused_title), style = TrailMix.type.heading, color = c.text)
            Text(stringResource(R.string.capture_paused_body), style = TrailMix.type.bodySmall, color = c.dim)
        }
    }
}

/** One finalized transcript line: teal timestamp (provenance: said), ink text. */
@Composable
internal fun TranscriptLineRow(line: TranscriptLine, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TmSpacing.m)) {
        Text(line.label, style = TrailMix.type.mono, color = TrailMix.colors.teal, modifier = Modifier.padding(top = 3.dp))
        Text(line.text, style = TrailMix.type.body, color = TrailMix.colors.text, modifier = Modifier.weight(1f))
    }
}

/** The compact live transcript: last few final lines plus the in-progress one in grey (C2). */
@Composable
internal fun LiveTranscriptCard(
    lines: List<TranscriptLine>,
    partial: String,
    ticker: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.capture_transcript_header),
                style = TrailMix.type.overline,
                color = c.dim,
                modifier = Modifier.weight(1f),
            )
            TmIconButton(TmIcons.ExpandContent, stringResource(R.string.capture_transcript_expand), onExpand)
        }
        val shown = if (ticker) lines.takeLast(1) else lines.takeLast(3)
        if (shown.isEmpty() && partial.isBlank()) {
            Text(stringResource(R.string.capture_transcript_empty), style = TrailMix.type.body, color = c.dim)
        }
        shown.forEach { TranscriptLineRow(it) }
        if (partial.isNotBlank()) {
            Text("…$partial", style = TrailMix.type.body, color = c.dim)
        }
    }
}

@Composable
internal fun SoFarCard(summary: String, modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Text(stringResource(R.string.capture_so_far), style = TrailMix.type.overline, color = c.dim)
        Text(summary, style = TrailMix.type.bodySmall, color = c.text)
    }
}

/**
 * C2: the typed notes field with its placeholder. [value] is owned by the caller so the shortcut
 * chips can edit at the cursor. Amber is reserved for provenance, and this caret is the one place
 * the design keeps it ("the typed-note field caret").
 */
@Composable
internal fun NotesField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        textStyle = TrailMix.type.body.copy(color = c.text),
        cursorBrush = SolidColor(c.amber),
        enabled = enabled,
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.capture_notes_placeholder),
                        style = TrailMix.type.body,
                        color = c.dim,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun ShortcutChips(onHeading: () -> Unit, onQuestion: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = TmSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        TmAssistChip(stringResource(R.string.capture_chip_heading), onHeading, leadingDrawable = TmIcons.Title)
        TmAssistChip(stringResource(R.string.capture_chip_question), onQuestion, leadingDrawable = TmIcons.Help)
    }
}

/** A 48dp round tonal icon control for the dock; [badge] > 0 shows a small count. */
@Composable
private fun DockIconButton(drawable: Int, description: String, onClick: () -> Unit, badge: Int = 0) {
    val c = TrailMix.colors
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(c.cardHigh)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TmIcon(drawable, description, tint = c.text)
        if (badge > 0) {
            Text(
                text = badge.toString(),
                style = TrailMix.type.mono,
                color = c.flag,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 6.dp),
            )
        }
    }
}

/** C2/C3: the one-thumb dock. Recording: Pause, Flag, End & merge. Paused: Resume, End & merge. */
@Composable
internal fun CaptureDock(
    paused: Boolean,
    flagCount: Int,
    noTranscript: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFlag: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flagDescription = if (flagCount > 0) {
        stringResource(R.string.capture_flag_with_count, flagCount)
    } else {
        stringResource(R.string.capture_flag)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (paused) {
            TmTonalButton(
                label = stringResource(R.string.capture_resume),
                onClick = onResume,
                icon = TmButtonIcon.Vector(Icons.Filled.PlayArrow),
            )
        } else {
            DockIconButton(TmIcons.Pause, stringResource(R.string.capture_pause), onPause)
            DockIconButton(TmIcons.Flag, flagDescription, onFlag, badge = flagCount)
        }
        TmButton(
            label = stringResource(if (noTranscript) R.string.capture_save_notes else R.string.capture_end_and_merge),
            onClick = onEnd,
            modifier = Modifier.weight(1f),
            icon = TmButtonIcon.Vector(Icons.Filled.Check),
        )
    }
}

/** C5: when the keyboard is up the header compresses to level + timer + pause + flag. */
@Composable
internal fun KeyboardHeader(
    state: CaptureUiState,
    level: Float,
    onMinimise: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFlag: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TmSpacing.xs, vertical = TmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MinimiseButton(onMinimise)
        TmRecordingIndicator(
            state = state.indicatorState(),
            elapsedLabel = state.elapsedLabel,
            level = level,
            compact = true,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(Modifier.weight(1f))
        if (state.paused) {
            TmIconButton(Icons.Filled.PlayArrow, stringResource(R.string.capture_resume), onResume)
        } else {
            TmIconButton(TmIcons.Pause, stringResource(R.string.capture_pause), onPause)
            TmIconButton(TmIcons.Flag, stringResource(R.string.capture_flag), onFlag)
        }
    }
}

/** C7: the full-height transcript, auto-following the newest line until the user scrolls up. */
@Composable
internal fun TranscriptSheet(
    lines: List<TranscriptLine>,
    partial: String,
    state: CaptureUiState,
    level: Float,
    onDismiss: () -> Unit,
    dock: @Composable () -> Unit,
) {
    val c = TrailMix.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemCount = lines.size + if (partial.isNotBlank()) 1 else 0
    var following by remember { mutableStateOf(true) }
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) following = atEnd
    }
    LaunchedEffect(itemCount) {
        if (following && itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    TmSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = TmSpacing.l, end = TmSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.capture_transcript_title),
                    style = TrailMix.type.title,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                TmRecordingIndicator(state.indicatorState(), state.elapsedLabel, level, compact = true)
                TmIconButton(Icons.Filled.Close, stringResource(R.string.action_close), onDismiss)
            }
            Box(modifier = Modifier.weight(1f, fill = false)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(TmSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(TmSpacing.m),
                ) {
                    items(lines.size, key = { i -> "${lines[i].label}-$i" }) { i -> TranscriptLineRow(lines[i]) }
                    if (partial.isNotBlank()) {
                        item { Text("…$partial", style = TrailMix.type.body, color = c.dim) }
                    }
                }
                if (!following) {
                    TmOutlinedButton(
                        label = stringResource(R.string.capture_transcript_latest),
                        onClick = {
                            following = true
                            scope.launch { if (itemCount > 0) listState.animateScrollToItem(itemCount - 1) }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TmSpacing.s),
                        icon = TmButtonIcon.Drawable(TmIcons.ArrowUp),
                    )
                }
            }
            dock()
        }
    }
}

/** C6: Recording options as a sheet: mic, phone audio, template, help, discard. */
@Composable
internal fun RecordingOptionsSheet(
    state: CaptureUiState,
    deviceAudioSupported: Boolean,
    templateLabel: String,
    onSelectInput: (Int) -> Unit,
    onTogglePhoneAudio: (Boolean) -> Unit,
    onTemplate: () -> Unit,
    onHelp: () -> Unit,
    onOutput: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    TmSheet(onDismiss = onDismiss, title = stringResource(R.string.capture_options_title)) {
        Column {
            Text(
                stringResource(R.string.capture_options_listen_to),
                style = TrailMix.type.overline,
                color = c.dim,
                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            )
            state.inputOptions.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onSelectInput(index) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = TmSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
                ) {
                    RadioButton(
                        selected = index == state.selectedInputIndex,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.text, unselectedColor = c.outline),
                    )
                    Text(option.label, style = TrailMix.type.body, color = c.text)
                }
            }
            TmSwitchRow(
                title = stringResource(R.string.capture_options_phone_audio),
                subtitle = stringResource(
                    if (deviceAudioSupported) R.string.capture_options_phone_audio_body else R.string.capture_options_phone_audio_unsupported,
                ),
                checked = state.deviceAudioActive,
                enabled = deviceAudioSupported,
                onCheckedChange = onTogglePhoneAudio,
            )
            TmSheetAction(stringResource(R.string.capture_options_template, templateLabel), onTemplate, drawable = TmIcons.Subject)
            TmSheetAction(stringResource(R.string.capture_options_hear), onHelp, drawable = TmIcons.Help)
            TmSheetAction(stringResource(R.string.capture_options_output), onOutput, drawable = TmIcons.Devices)
            TmSheetAction(
                stringResource(R.string.capture_options_discard),
                onDiscard,
                destructive = true,
                drawable = TmIcons.Undo,
            )
            Text(
                text = stringResource(
                    when (state.engineKind) {
                        EngineKind.MLKIT -> R.string.capture_recognizer_mlkit
                        EngineKind.LEGACY -> R.string.capture_recognizer_legacy
                        EngineKind.NONE -> R.string.capture_recognizer_none
                    },
                ),
                style = TrailMix.type.caption,
                color = c.dim,
                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            )
        }
    }
}

/** C6: Template choice, now out of the main screen. */
@Composable
internal fun TemplateSheet(
    options: List<TemplateOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    TmSheet(onDismiss = onDismiss, title = stringResource(R.string.capture_template_title)) {
        Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.capture_options_template_body),
                style = TrailMix.type.bodySmall,
                color = c.dim,
                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            )
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.RadioButton) { onSelect(option.stored) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = TmSpacing.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
                ) {
                    RadioButton(
                        selected = option.stored == selected,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.text, unselectedColor = c.outline),
                    )
                    Text(option.label, style = TrailMix.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** C8: merging is a real state with steps and "safe to leave". */
@Composable
internal fun MergingPanel(
    state: CaptureUiState,
    mergeStatus: MergeStatus?,
    transcriptLines: Int,
    typedLines: Int,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val writing = mergeStatus?.stage() == MergeStage.WRITING
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TmSpacing.l)) {
        TmLongTaskProgress(
            title = stringResource(R.string.capture_merge_title),
            detail = stringResource(R.string.capture_merge_detail, state.elapsedLabel),
            progress = mergeStatus?.progress(),
            counter = mergeStatus?.counter(),
            reassurance = stringResource(R.string.capture_merge_safe),
        )
        Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
            TmStepLine(stringResource(R.string.capture_merge_step_transcript, transcriptLines), done = true)
            TmStepLine(stringResource(R.string.capture_merge_step_notes, typedLines), done = true)
            TmStepLine(
                text = mergeStatus?.counter()?.let { stringResource(R.string.capture_merge_step_summarizing_count, it) }
                    ?: stringResource(R.string.capture_merge_step_summarizing),
                done = writing,
            )
            TmStepLine(stringResource(R.string.capture_merge_step_writing), done = false)
        }
        TmOutlinedButton(stringResource(R.string.capture_merge_back_home), onClick = onBackToHome)
    }
}

/** C5: a first-run explanation shown once, before any system permission dialog. */
@Composable
internal fun PermissionIntro(onContinue: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Column(modifier = modifier.fillMaxWidth().padding(TmSpacing.l), verticalArrangement = Arrangement.spacedBy(TmSpacing.l)) {
        Text(stringResource(R.string.capture_perm_title), style = TrailMix.type.display, color = c.text)
        PermissionRow(TmIcons.Mic, R.string.capture_perm_mic_title, R.string.capture_perm_mic_body)
        PermissionRow(TmIcons.Chat, R.string.capture_perm_notif_title, R.string.capture_perm_notif_body)
        Text(stringResource(R.string.capture_perm_note), style = TrailMix.type.bodySmall, color = c.dim)
        TmButton(stringResource(R.string.capture_perm_continue), onClick = onContinue, modifier = Modifier.fillMaxWidth())
        TmOutlinedButton(stringResource(R.string.action_close), onClick = onClose, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun PermissionRow(icon: Int, title: Int, body: Int) {
    val c = TrailMix.colors
    Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.l), verticalAlignment = Alignment.Top) {
        TmIcon(icon, null, tint = c.text)
        Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
            Text(stringResource(title), style = TrailMix.type.heading, color = c.text)
            Text(stringResource(body), style = TrailMix.type.bodySmall, color = c.dim)
        }
    }
}
