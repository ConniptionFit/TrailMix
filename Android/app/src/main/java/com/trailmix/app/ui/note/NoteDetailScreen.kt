package com.trailmix.app.ui.note

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.ai.RegeneratePolicy
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredEdits
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.ui.components.ActiveCaptureCard
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmButtonIcon
import com.trailmix.app.ui.components.TmCloseButton
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmFilterChip
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmLongTaskProgress
import com.trailmix.app.ui.components.TmMenuItem
import com.trailmix.app.ui.components.TmOverflowMenu
import com.trailmix.app.ui.components.TmSegmented
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmSheetAction
import com.trailmix.app.ui.components.TmSnackbarHost
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTonalButton
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.export.PhotoPickerSheet
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The widest the reading column grows; on a tablet the note stays a comfortable line length. */
private val ReadingWidth = 680.dp

@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    /** Opens the transcript, scrolled to the line with this `mm:ss` label when there is one. */
    onOpenTranscript: (String?) -> Unit,
    onOpenChat: () -> Unit,
    onResume: () -> Unit,
    /** CAP-10/Part 3.2: jump back into a capture that's live in the background. */
    onOpenActiveCapture: () -> Unit = {},
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    // PERF-04: activeCapture is not collected here; ActiveCaptureCard collects its own flows.
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    val photoPermissionGranted by viewModel.photoPermissionGranted.collectAsStateWithLifecycle()
    val matchedPhotos by viewModel.matchedPhotos.collectAsStateWithLifecycle()
    val templateOptions by viewModel.templateOptions.collectAsStateWithLifecycle()
    val regenerating by viewModel.regenerating.collectAsStateWithLifecycle()
    val mergeStatus by viewModel.mergeStatus.collectAsStateWithLifecycle()
    val rebuildTemplate by viewModel.rebuildTemplate.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val current = note ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Flat edit (legacy notes with no structure, or a hand-edited body) vs structure edit (N4).
    var flatEditing by remember(current.id) { mutableStateOf(false) }
    var titleDraft by remember(current.id) { mutableStateOf("") }
    var bodyDraft by remember(current.id) { mutableStateOf("") }
    var summaryDraft by remember(current.id) { mutableStateOf<StructuredSummary?>(null) }
    var summaryBaseline by remember(current.id) { mutableStateOf<StructuredSummary?>(null) }
    val structureEditing = summaryDraft != null
    val editing = flatEditing || structureEditing
    var showDiscard by remember { mutableStateOf(false) }

    var showFormatPicker by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var showRebuild by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var reordering by remember(current.id) { mutableStateOf(false) }
    var sourceSheet by remember { mutableStateOf<SourceSheetData?>(null) }

    // 0 = Note (default), 1 = My notes (the raw typed fragments).
    var tab by remember(current.id) { mutableStateOf(0) }
    var editingRaw by remember(current.id) { mutableStateOf(false) }
    var rawDraft by remember(current.id) { mutableStateOf("") }
    var rawJustSaved by remember(current.id) { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val summary = current.structuredSummary
    val structuredEditable = summary != null && current.bodyOverride == null
    val editCount = RegeneratePolicy.editsToReplace(
        bodyOverride = current.bodyOverride,
        structuredEdits = summary?.let(StructuredEdits::editCount) ?: 0,
    )

    val shareChooserTitle = stringResource(R.string.note_share)

    fun shareNote(format: ExportFormat) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, current.title)
            putExtra(Intent.EXTRA_TEXT, current.toMarkdown(format = format))
        }
        context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
    }

    fun enterEdit() {
        titleDraft = current.title
        if (structuredEditable && summary != null) {
            val shaped = StructuredEdits.asSections(summary)
            summaryBaseline = shaped
            summaryDraft = shaped
        } else {
            bodyDraft = current.displayBody
            flatEditing = true
        }
    }

    fun leaveEdit() {
        flatEditing = false
        summaryDraft = null
        summaryBaseline = null
        showDiscard = false
    }

    val dirty = if (structureEditing) {
        titleDraft != current.title || summaryDraft != summaryBaseline
    } else {
        titleDraft != current.title || bodyDraft != current.displayBody
    }

    fun requestLeaveEdit() {
        if (dirty) showDiscard = true else leaveEdit()
    }

    if (showFormatPicker) {
        ExportFormatPickerDialog(
            initialFormat = defaultExportFormat,
            onConfirm = { format ->
                showFormatPicker = false
                shareNote(format)
            },
            onDismiss = { showFormatPicker = false },
        )
    }

    if (showPhotoPicker) {
        LaunchedEffect(Unit) {
            if (photoPermissionGranted) viewModel.loadMatchedPhotos()
        }
        PhotoPickerSheet(
            hasPermission = photoPermissionGranted,
            photos = matchedPhotos,
            initiallySelected = current.exportedPhotoUris.toSet(),
            onPermissionGranted = { viewModel.refreshPhotoPermission() },
            onConfirm = { uris ->
                showPhotoPicker = false
                viewModel.setSelectedPhotos(uris)
            },
            onDismiss = { showPhotoPicker = false },
        )
    }

    if (showRebuild) {
        RebuildSheet(
            templates = templateOptions,
            currentStored = current.template ?: "NONE",
            edits = editCount,
            onDismiss = { showRebuild = false },
            onRebuild = { stored ->
                showRebuild = false
                viewModel.regenerate(stored)
            },
        )
    }

    if (showDelete) {
        TmConfirmDialog(
            title = stringResource(R.string.note_delete_title),
            body = stringResource(R.string.note_delete_body),
            confirmLabel = stringResource(R.string.note_delete_confirm),
            destructive = true,
            onConfirm = {
                showDelete = false
                viewModel.deleteNote(onDeleted = onBack)
            },
            onDismiss = { showDelete = false },
        )
    }

    if (showDiscard) {
        TmConfirmDialog(
            title = stringResource(R.string.note_edit_discard_title),
            body = stringResource(R.string.note_edit_discard_body),
            confirmLabel = stringResource(R.string.note_edit_discard),
            destructive = true,
            onConfirm = ::leaveEdit,
            onDismiss = { showDiscard = false },
        )
    }

    sourceSheet?.let { data ->
        SourceSheet(
            data = data,
            onCopy = {
                clipboard.setText(AnnotatedString(data.text))
                sourceSheet = null
            },
            onEdit = {
                sourceSheet = null
                if (!regenerating) enterEdit()
            },
            canEdit = !regenerating,
            onDismiss = { sourceSheet = null },
        )
    }

    // While editing, Back asks before dropping changes; otherwise it closes the raw-notes editor.
    BackHandler(enabled = editing) { requestLeaveEdit() }
    BackHandler(enabled = editingRaw) { editingRaw = false }

    val exportedMessage = stringResource(R.string.note_exported)
    val exportFailedMessage = stringResource(R.string.note_export_failed)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        if (editing) {
            EditBar(
                onClose = ::requestLeaveEdit,
                onSave = {
                    val draft = summaryDraft
                    if (draft != null) {
                        viewModel.saveStructuredEdits(titleDraft, draft) { leaveEdit() }
                    } else {
                        viewModel.saveEdits(titleDraft, bodyDraft) { leaveEdit() }
                    }
                },
            )
        } else {
            NoteTopBar(
                navigation = { TmBackButton(onBack) },
                actions = {
                    TmIconButton(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.note_share),
                        onClick = { showFormatPicker = true },
                    )
                    TmOverflowMenu(
                        contentDescription = stringResource(R.string.note_more),
                        items = buildList {
                            add(TmMenuItem(stringResource(R.string.note_menu_continue), onResume, enabled = !regenerating))
                            add(TmMenuItem(stringResource(R.string.note_menu_edit), ::enterEdit, enabled = !regenerating))
                            add(TmMenuItem(stringResource(R.string.note_menu_rebuild), { showRebuild = true }, enabled = !regenerating))
                            if (summary != null && summary.sections.size >= 2 && tab == 0) {
                                add(
                                    TmMenuItem(
                                        stringResource(
                                            if (reordering) R.string.note_reorder_done else R.string.note_menu_reorder,
                                        ),
                                        { reordering = !reordering },
                                    ),
                                )
                            }
                            add(
                                TmMenuItem(
                                    stringResource(R.string.note_menu_photos),
                                    { showPhotoPicker = true },
                                    badge = current.exportedPhotoUris.size.takeIf { it > 0 }?.toString(),
                                ),
                            )
                            add(
                                TmMenuItem(
                                    stringResource(R.string.note_menu_export),
                                    {
                                        viewModel.exportNow { ok ->
                                            viewModel.postMessage(if (ok) exportedMessage else exportFailedMessage)
                                        }
                                    },
                                ),
                            )
                            add(TmMenuItem(stringResource(R.string.note_menu_delete), { showDelete = true }))
                        },
                    )
                },
            )
        }

        // PERF-04: collects its own state so its 1 Hz tick recomposes only this card.
        if (!editing) {
            ActiveCaptureCard(
                activeCapture = viewModel.activeCapture,
                level = viewModel.level,
                mergeStatus = viewModel.mergeStatus,
                onOpen = onOpenActiveCapture,
                showBuilding = !regenerating,
                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = ReadingWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TmSpacing.l),
            ) {
                val draft = summaryDraft
                when {
                    draft != null -> StructureEditor(
                        title = titleDraft,
                        onTitleChange = { titleDraft = it },
                        summary = draft,
                        onSummaryChange = { summaryDraft = it },
                        modifier = Modifier.padding(bottom = TmSpacing.xxl),
                    )

                    flatEditing -> FlatEditor(
                        title = titleDraft,
                        onTitleChange = { titleDraft = it },
                        body = bodyDraft,
                        onBodyChange = { bodyDraft = it },
                    )

                    else -> ReadContent(
                        note = current,
                        summary = summary,
                        editCount = editCount,
                        tab = tab,
                        onTab = {
                            tab = it
                            editingRaw = false
                        },
                        regenerating = regenerating,
                        rebuildTitle = stringResource(
                            R.string.note_rebuilding_title,
                            templateOptions.firstOrNull { it.stored == rebuildTemplate }?.label.orEmpty(),
                        ),
                        mergeStatus = mergeStatus,
                        reordering = reordering,
                        editingRaw = editingRaw,
                        rawDraft = rawDraft,
                        onRawDraft = { rawDraft = it },
                        rawJustSaved = rawJustSaved,
                        onToggleSources = viewModel::toggleShowSources,
                        onOpenTranscript = { onOpenTranscript(it) },
                        onToggleStep = viewModel::setActionDone,
                        onMoveSection = viewModel::moveSummarySection,
                        onShowSource = { sourceSheet = it },
                        onCancelRaw = { editingRaw = false },
                        onSaveRaw = {
                            viewModel.saveRawNotes(rawDraft) {
                                editingRaw = false
                                rawJustSaved = true
                            }
                        },
                        onRebuild = { showRebuild = true },
                    )
                }
            }
        }

        TmSnackbarHost(snackbarHostState)

        if (!editing) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                HorizontalDivider(color = c.border)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
                    horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
                ) {
                    if (tab == 1) {
                        TmTonalButton(
                            label = stringResource(R.string.note_edit),
                            onClick = {
                                rawDraft = current.typedFragments
                                rawJustSaved = false
                                editingRaw = true
                            },
                            icon = TmButtonIcon.Vector(Icons.Filled.Edit),
                            enabled = !regenerating,
                            modifier = Modifier.weight(1f),
                        )
                        TmTonalButton(
                            label = stringResource(R.string.note_mine_rebuild_button),
                            onClick = { showRebuild = true },
                            enabled = !regenerating,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TmTonalButton(
                            label = stringResource(R.string.note_transcript),
                            onClick = { onOpenTranscript(null) },
                            icon = TmButtonIcon.Drawable(TmIcons.Subject),
                            modifier = Modifier.weight(1f),
                        )
                        TmTonalButton(
                            label = stringResource(R.string.note_chat),
                            onClick = onOpenChat,
                            icon = TmButtonIcon.Drawable(TmIcons.Chat),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteTopBar(
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    // Content first: the note's own title sits in the body, so the bar carries no title text.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TmSpacing.xs, end = TmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation()
        Box(modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
private fun EditBar(onClose: () -> Unit, onSave: () -> Unit) {
    TmTopBar(
        title = stringResource(R.string.note_edit_title),
        navigation = { TmCloseButton(onClose, contentDescription = stringResource(R.string.note_edit_close)) },
        actions = { TmTextButton(stringResource(R.string.note_save), onClick = onSave) },
    )
}

/** The reading view: header, the Note / My notes switch, then the body for the chosen tab. */
@Composable
private fun ReadContent(
    note: NoteEntity,
    summary: StructuredSummary?,
    editCount: Int,
    tab: Int,
    onTab: (Int) -> Unit,
    regenerating: Boolean,
    rebuildTitle: String,
    mergeStatus: MergeStatus?,
    reordering: Boolean,
    editingRaw: Boolean,
    rawDraft: String,
    onRawDraft: (String) -> Unit,
    rawJustSaved: Boolean,
    onToggleSources: () -> Unit,
    onOpenTranscript: (String) -> Unit,
    onToggleStep: (Int, Boolean) -> Unit,
    onMoveSection: (Int, Int) -> Unit,
    onShowSource: (SourceSheetData) -> Unit,
    onCancelRaw: () -> Unit,
    onSaveRaw: () -> Unit,
    onRebuild: () -> Unit,
) {
    val c = TrailMix.colors
    val inCall = stringResource(R.string.note_meta_in_call)
    val edited = stringResource(R.string.note_meta_edited)
    val meta = buildList {
        add(relativeDay(note.createdAtEpochMs))
        add(durationLabel(note.durationMs))
        note.meetingTitle?.let { add(it) }
        if (note.capturedInCall) add(inCall)
        if (editCount > 0) add(edited)
    }.joinToString(" · ")

    Text(text = meta, style = TrailMix.type.caption, color = c.dim)
    Text(
        text = note.title,
        style = TrailMix.type.display,
        color = c.text,
        modifier = Modifier.padding(top = TmSpacing.xs),
    )
    if (note.attendees.isNotEmpty()) {
        Text(
            text = note.attendees.joinToString(", "),
            style = TrailMix.type.bodySmall,
            color = c.dim,
            modifier = Modifier.padding(top = TmSpacing.xs),
        )
    }

    Row(
        modifier = Modifier.padding(top = TmSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
    ) {
        TmSegmented(
            options = listOf(
                0 to stringResource(R.string.note_tab_note),
                1 to stringResource(R.string.note_tab_mine),
            ),
            selected = tab,
            onSelect = onTab,
        )
        // Sources works on edited notes too: provenance survives structured edits.
        if (tab == 0 && note.bodyOverride == null) {
            TmFilterChip(
                label = stringResource(R.string.note_sources),
                selected = note.showSources,
                onClick = onToggleSources,
                leadingDrawable = if (note.showSources) null else TmIcons.Visibility,
            )
        }
    }

    if (tab == 0 && note.showSources && note.bodyOverride == null) {
        SourcesLegend(modifier = Modifier.padding(top = TmSpacing.m))
    }

    Column(modifier = Modifier.padding(top = TmSpacing.m, bottom = TmSpacing.xxl)) {
        if (tab == 1) {
            MyNotesPane(
                raw = note.typedFragments,
                editing = editingRaw,
                draft = rawDraft,
                onDraftChange = onRawDraft,
                busy = regenerating,
                showRebuildBanner = rawJustSaved,
                onRebuild = onRebuild,
                onCancel = onCancelRaw,
                onSave = onSaveRaw,
            )
            return@Column
        }
        if (regenerating) {
            TmLongTaskProgress(
                title = rebuildTitle,
                detail = stringResource(R.string.note_rebuilding_detail),
                progress = mergeStatus?.progress(),
                counter = mergeStatus?.counter(),
                reassurance = stringResource(R.string.note_rebuilding_safe),
                modifier = Modifier.padding(bottom = TmSpacing.l),
            )
        }
        // The old note stays readable while a rebuild runs, dimmed so it reads as not final.
        Column(modifier = Modifier.alpha(if (regenerating) 0.5f else 1f)) {
            when {
                note.bodyOverride != null -> Text(
                    text = note.bodyOverride!!,
                    style = TrailMix.type.body,
                    color = c.text,
                )

                summary != null -> StructuredNoteBody(
                    summary = summary,
                    showSources = note.showSources,
                    enabled = !regenerating,
                    reordering = reordering,
                    onOpenTranscript = onOpenTranscript,
                    onToggleStep = onToggleStep,
                    onMoveSection = onMoveSection,
                    onShowSource = onShowSource,
                )

                else -> FlatBody(note)
            }
        }
    }
}

/** Notes made before structured summaries: the merged text, tinted by source when Sources is on. */
@Composable
private fun FlatBody(note: NoteEntity) {
    val c = TrailMix.colors
    val body = buildAnnotatedString {
        note.segments.forEachIndexed { i, segment ->
            if (i > 0) append(" ")
            if (note.showSources) {
                withStyle(
                    SpanStyle(
                        background = when (segment.source) {
                            Provenance.FRAGMENT -> c.amberTint
                            Provenance.TRANSCRIPT -> c.tealTint
                        },
                        // Fixed dark text on pale tints in BOTH modes (design requirement)
                        color = c.spanText,
                    ),
                ) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
    Text(text = body, style = TrailMix.type.body, color = c.text)
}

/** The old whole-body editor, kept for notes with no structure or with a hand-edited body. */
@Composable
private fun FlatEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
) {
    val c = TrailMix.colors
    BasicTextField(
        value = title,
        onValueChange = onTitleChange,
        modifier = Modifier.fillMaxWidth().padding(top = TmSpacing.xs, bottom = TmSpacing.m),
        textStyle = TrailMix.type.title.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
    )
    HorizontalDivider(color = c.border)
    BasicTextField(
        value = body,
        onValueChange = onBodyChange,
        modifier = Modifier.fillMaxWidth().padding(top = TmSpacing.m, bottom = TmSpacing.xxl),
        textStyle = TrailMix.type.body.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
    )
}

/**
 * The raw typed fragments, plain proportional text, editable. Saving persists only the
 * `typedFragments` column; Rebuild then re-runs the merge with them.
 */
@Composable
private fun MyNotesPane(
    raw: String,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    busy: Boolean,
    showRebuildBanner: Boolean,
    onRebuild: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val c = TrailMix.colors
    if (editing) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = TmSpacing.l),
            textStyle = TrailMix.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.m)) {
            TmTextButton(stringResource(R.string.note_cancel), onClick = onCancel)
            TmTextButton(stringResource(R.string.note_save), onClick = onSave)
        }
        return
    }
    if (showRebuildBanner && !busy) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = TmSpacing.l)
                .background(c.card, TrailMix.shapes.medium)
                .padding(TmSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.note_mine_banner),
                style = TrailMix.type.bodySmall,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            TmTextButton(stringResource(R.string.note_mine_rebuild), onClick = onRebuild)
        }
    }
    if (raw.isBlank()) {
        Text(
            text = stringResource(R.string.note_mine_empty),
            style = TrailMix.type.bodySmall,
            color = c.dim,
        )
        return
    }
    val lines = raw.lines()
    Text(
        text = stringResource(R.string.note_mine_heading, lines.count { it.isNotBlank() }),
        style = TrailMix.type.overline,
        color = c.dim,
        modifier = Modifier.padding(bottom = TmSpacing.s),
    )
    lines.forEach { line ->
        when {
            line.isBlank() -> Unit
            line.startsWith("# ") -> Text(
                text = line.removePrefix("# "),
                style = TrailMix.type.heading,
                color = c.text,
                modifier = Modifier.padding(top = TmSpacing.m),
            )

            else -> Text(text = line, style = TrailMix.type.body, color = c.text)
        }
    }
}

/** Long-press sheet: where a point came from, plus Copy and Edit. */
@Composable
private fun SourceSheet(
    data: SourceSheetData,
    canEdit: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    TmSheet(onDismiss = onDismiss, title = stringResource(R.string.note_sheet_title)) {
        Column(
            modifier = Modifier.padding(horizontal = TmSpacing.l),
            verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
        ) {
            Text(data.text, style = TrailMix.type.body, color = c.text)
            val caption = when {
                data.source == Provenance.FRAGMENT -> stringResource(R.string.note_sheet_typed)
                data.timestampLabel != null -> stringResource(
                    if (data.edited) R.string.note_sheet_recording_edited else R.string.note_sheet_recording,
                    data.timestampLabel,
                )

                else -> null
            }
            if (caption != null) {
                Text(caption, style = TrailMix.type.caption, color = sourceColor(data.source))
            }
            Text(
                text = data.excerpt?.let { "“$it”" } ?: stringResource(R.string.note_sheet_no_quote),
                style = TrailMix.type.bodySmall,
                color = c.dim,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.card, TrailMix.shapes.medium)
                    .padding(TmSpacing.m),
            )
        }
        TmSheetAction(stringResource(R.string.note_copy), onClick = onCopy)
        TmSheetAction(stringResource(R.string.note_edit), onClick = onEdit, enabled = canEdit)
    }
}

private fun relativeDay(epochMs: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = epochMs
    val that = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when {
        that == today -> "Today"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

private fun durationLabel(durationMs: Long): String {
    val min = (durationMs / 60_000).coerceAtLeast(0)
    return if (min < 1) "<1 min" else "$min min"
}
