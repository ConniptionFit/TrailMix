package com.trailmix.app.ui.transcript

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.TranscriptLine
import com.trailmix.app.ui.components.TmBackButton
import com.trailmix.app.ui.components.TmButton
import com.trailmix.app.ui.components.TmFilterChip
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmSheetAction
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTextField
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.export.ExportFormatPickerDialog
import com.trailmix.app.ui.home.SearchField
import com.trailmix.app.ui.note.NoteDetailViewModel
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.delay

/** How long the line you jumped to stays tinted. */
private const val JUMP_TINT_MS = 3_000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptScreen(
    onBack: () -> Unit,
    /** UX-19/UX-20: scroll to and highlight the transcript line with this label, if any. */
    highlightLabel: String? = null,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val note by viewModel.note.collectAsStateWithLifecycle()
    val defaultExportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current

    var showFormatPicker by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(TranscriptFilter.ALL) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitCursor by remember { mutableIntStateOf(0) }
    // T2: the line whose action sheet is open, and T3 the line being corrected.
    var sheetIndex by remember { mutableStateOf<Int?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editDraft by remember { mutableStateOf("") }

    val lines = note?.transcript.orEmpty()
    val title = note?.title.orEmpty()
    val flaggedIndices = remember(lines, note?.flaggedLabels) {
        note?.flaggedLabels.orEmpty().mapNotNull { flagLineIndex(lines, it) }.toSet()
    }
    val hasLanes = remember(lines) { lines.any { it.speechSource != null } }
    val visible = remember(lines, filter, flaggedIndices) { visibleIndices(lines, filter, flaggedIndices) }
    val items = remember(lines, visible, flaggedIndices) { buildTranscriptItems(lines, visible, flaggedIndices) }
    val hits = remember(lines, query, searching) { if (searching) searchHits(lines, query).filter { it in visible } else emptyList() }

    // The jump target tints for a few seconds, then settles.
    var jumpIndex by remember(lines, highlightLabel) {
        mutableStateOf(highlightLabel?.let { label -> lines.indexOfFirst { it.label == label } }?.takeIf { it >= 0 })
    }
    val listState = rememberLazyListState()
    fun positionOf(lineIndex: Int): Int = items.indexOfFirst { it is TranscriptItem.Line && it.index == lineIndex }

    // UX-19/UX-20: land on the moment a search matched, not somewhere in a 90-minute transcript.
    LaunchedEffect(jumpIndex, items) {
        val target = jumpIndex ?: return@LaunchedEffect
        val position = positionOf(target)
        if (position >= 0) listState.scrollToItem(position)
        delay(JUMP_TINT_MS)
        jumpIndex = null
    }
    LaunchedEffect(hits, hitCursor) {
        val target = hits.getOrNull(hitCursor.coerceIn(0, (hits.size - 1).coerceAtLeast(0))) ?: return@LaunchedEffect
        val position = positionOf(target)
        if (position >= 0) listState.animateScrollToItem(position)
    }

    fun shareQuote(line: TranscriptLine) {
        val who = speakerName(line)
        val text = buildString {
            append("\"").append(line.text).append("\"\n\n— ")
            if (who != null) append(who).append(", ")
            append(line.label).append(", ").append(title)
        }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                resources.getString(R.string.transcript_share_quote_chooser),
            ),
        )
    }

    fun shareTranscript(format: ExportFormat) {
        val body = if (format == ExportFormat.PLAIN_TEXT) {
            lines.joinToString("\n") { "${it.label}  ${it.text}" }
        } else {
            lines.joinToString("\n") { "**${it.label}** — ${it.text}" }
        }
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, body)
                },
                resources.getString(R.string.transcript_share_chooser),
            ),
        )
    }

    if (showFormatPicker) {
        ExportFormatPickerDialog(
            initialFormat = defaultExportFormat,
            onConfirm = { format ->
                showFormatPicker = false
                shareTranscript(format)
            },
            onDismiss = { showFormatPicker = false },
        )
    }

    sheetIndex?.let { index ->
        val line = lines.getOrNull(index)
        if (line == null) {
            sheetIndex = null
        } else {
            TmSheet(
                onDismiss = { sheetIndex = null },
                title = listOfNotNull(speakerName(line), line.label).joinToString(" · "),
            ) {
                Text(
                    text = "\"${line.text}\"",
                    style = TrailMix.type.bodySmall,
                    color = c.dim,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                )
                TmSheetAction(
                    label = stringResource(R.string.transcript_copy),
                    drawable = TmIcons.ContentCopy,
                    onClick = {
                        clipboard.setText(AnnotatedString(line.text))
                        sheetIndex = null
                    },
                )
                TmSheetAction(
                    label = stringResource(R.string.transcript_share_quote),
                    icon = Icons.Filled.Share,
                    onClick = {
                        sheetIndex = null
                        shareQuote(line)
                    },
                )
                TmSheetAction(
                    label = stringResource(R.string.transcript_fix),
                    icon = Icons.Filled.Edit,
                    onClick = {
                        sheetIndex = null
                        editingIndex = index
                        editDraft = line.text
                    },
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TmTopBar(
            title = stringResource(R.string.transcript_title),
            subtitle = if (lines.isEmpty()) null else stringResource(R.string.transcript_subtitle, title, lines.size),
            navigation = { TmBackButton(onClick = onBack, contentDescription = stringResource(R.string.action_back)) },
            actions = {
                if (lines.isNotEmpty()) {
                    TmIconButton(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.transcript_search),
                        onClick = {
                            searching = !searching
                            if (!searching) query = ""
                        },
                    )
                    TmIconButton(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.transcript_share),
                        onClick = { showFormatPicker = true },
                    )
                }
            },
        )

        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.transcript_empty),
                style = TrailMix.type.body,
                color = c.dim,
                modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
            )
            return@Column
        }

        Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().weight(1f)) {
            if (searching) {
                Row(
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        query = query,
                        onQueryChange = {
                            query = it
                            hitCursor = 0
                        },
                        modifier = Modifier.weight(1f),
                        hint = stringResource(R.string.transcript_search_hint),
                    )
                    if (hits.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.transcript_hit_position, hitCursor.coerceIn(0, hits.size - 1) + 1, hits.size),
                            style = TrailMix.type.mono,
                            color = c.dim,
                            modifier = Modifier.padding(horizontal = TmSpacing.s),
                        )
                        TmIconButton(
                            drawable = TmIcons.ArrowUp,
                            contentDescription = stringResource(R.string.transcript_prev_hit),
                            onClick = { hitCursor = (hitCursor - 1 + hits.size) % hits.size },
                        )
                        TmIconButton(
                            drawable = TmIcons.ArrowDown,
                            contentDescription = stringResource(R.string.transcript_next_hit),
                            onClick = { hitCursor = (hitCursor + 1) % hits.size },
                        )
                    } else if (query.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.transcript_no_hits),
                            style = TrailMix.type.caption,
                            color = c.dim,
                            modifier = Modifier.padding(horizontal = TmSpacing.s),
                        )
                    }
                }
            }
            if (flaggedIndices.isNotEmpty() || hasLanes) {
                Row(
                    modifier = Modifier.padding(horizontal = TmSpacing.l),
                    horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
                ) {
                    TmFilterChip(stringResource(R.string.transcript_filter_all), filter == TranscriptFilter.ALL, { filter = TranscriptFilter.ALL })
                    if (flaggedIndices.isNotEmpty()) {
                        TmFilterChip(
                            label = stringResource(R.string.transcript_filter_flagged, flaggedIndices.size),
                            selected = filter == TranscriptFilter.FLAGGED,
                            onClick = { filter = TranscriptFilter.FLAGGED },
                            leadingDrawable = TmIcons.Flag,
                        )
                    }
                    if (hasLanes) {
                        TmFilterChip(stringResource(R.string.transcript_filter_me), filter == TranscriptFilter.ME, { filter = TranscriptFilter.ME })
                        TmFilterChip(stringResource(R.string.transcript_filter_them), filter == TranscriptFilter.THEM, { filter = TranscriptFilter.THEM })
                    }
                }
            }

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.transcript_filter_empty),
                    style = TrailMix.type.body,
                    color = c.dim,
                    modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
                )
            }
            val activeHit = hits.getOrNull(hitCursor.coerceIn(0, (hits.size - 1).coerceAtLeast(0)))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            ) {
                items(
                    items,
                    key = { item ->
                        when (item) {
                            is TranscriptItem.Header -> "h${item.firstIndex}"
                            is TranscriptItem.Line -> "l${item.index}"
                        }
                    },
                ) { item ->
                    when (item) {
                        is TranscriptItem.Header -> Text(
                            text = item.speaker,
                            style = TrailMix.type.heading,
                            color = c.text,
                            modifier = Modifier.padding(start = TmSpacing.l, end = TmSpacing.l, top = TmSpacing.l),
                        )

                        is TranscriptItem.Line -> if (item.index == editingIndex) {
                            LineEditor(
                                draft = editDraft,
                                onDraftChange = { editDraft = it },
                                onCancel = { editingIndex = null },
                                onSave = {
                                    viewModel.updateTranscriptLine(item.index, editDraft) { editingIndex = null }
                                },
                            )
                        } else {
                            // T2: a tap does nothing, so a thumb resting on the list while scrolling
                            // can't start anything. A long press opens the line's sheet.
                            LineRow(
                                item = item,
                                query = if (searching) query else "",
                                tinted = item.index == jumpIndex,
                                activeHit = item.index == activeHit,
                                onLongClick = { sheetIndex = item.index },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LineRow(
    item: TranscriptItem.Line,
    query: String,
    tinted: Boolean,
    activeHit: Boolean,
    onLongClick: () -> Unit,
) {
    val c = TrailMix.colors
    val tint = TrailMix.colors.tealTint
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tinted || activeHit) Modifier.background(tint) else Modifier)
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.widthIn(min = 52.dp)) {
            Text(item.line.label, style = TrailMix.type.mono, color = c.dim)
            if (item.flagged) {
                TmIcon(TmIcons.Flag, contentDescription = stringResource(R.string.transcript_flagged), tint = c.flag, size = 16.dp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlighted(item.line.text, query, tint),
                style = TrailMix.type.body,
                color = c.text,
            )
            if (item.line.edited) {
                Text(stringResource(R.string.transcript_edited), style = TrailMix.type.caption, color = c.dim)
            }
        }
    }
}

/** The line's text with every search hit tinted. */
private fun highlighted(text: String, query: String, tint: androidx.compose.ui.graphics.Color): AnnotatedString {
    val ranges = matchRanges(text, query)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var at = 0
        ranges.forEach { range ->
            append(text.substring(at, range.first))
            withStyle(SpanStyle(background = tint)) { append(text.substring(range.first, range.last + 1)) }
            at = range.last + 1
        }
        append(text.substring(at))
    }
}

/** T3: correct a line in place with full-size Cancel and Save. */
@Composable
private fun LineEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        TmTextField(
            label = stringResource(R.string.transcript_fix),
            value = draft,
            onValueChange = onDraftChange,
            singleLine = false,
            minHeight = 96,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.s), modifier = Modifier.fillMaxWidth()) {
            TmTextButton(stringResource(R.string.transcript_cancel), onClick = onCancel)
            TmButton(label = stringResource(R.string.transcript_save), onClick = onSave, enabled = draft.isNotBlank())
        }
    }
}
