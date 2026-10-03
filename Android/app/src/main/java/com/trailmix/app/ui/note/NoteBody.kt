package com.trailmix.app.ui.note

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.model.ActionItem
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredEdits
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.SummaryBullet
import com.trailmix.app.data.model.displayText
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/** What the long-press sheet shows about one bullet or step. */
internal data class SourceSheetData(
    val text: String,
    val source: Provenance,
    val excerpt: String?,
    val timestampLabel: String?,
    val edited: Boolean,
)

/** Amber = typed by you, teal = said in the recording. Provenance only, never an action color. */
@Composable
internal fun sourceColor(source: Provenance): Color = when (source) {
    Provenance.FRAGMENT -> TrailMix.colors.amber
    Provenance.TRANSCRIPT -> TrailMix.colors.teal
}

/** A2/A7: provenance is a 6dp dot and a timestamp; the text itself is always ink. */
@Composable
private fun ProvenanceDot(color: Color, modifier: Modifier = Modifier, size: Int = 6) {
    Box(modifier = modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Key for the two dot colors, shown only while Sources is on. */
@Composable
internal fun SourcesLegend(modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProvenanceDot(c.amber)
            Text(
                stringResource(R.string.note_legend_typed),
                style = TrailMix.type.caption,
                color = c.dim,
                modifier = Modifier.padding(start = TmSpacing.xs + 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProvenanceDot(c.teal)
            Text(
                stringResource(R.string.note_legend_said),
                style = TrailMix.type.caption,
                color = c.dim,
                modifier = Modifier.padding(start = TmSpacing.xs + 2.dp),
            )
        }
    }
}

@Composable
private fun TimestampLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = TrailMix.type.mono,
        color = TrailMix.colors.teal,
        modifier = modifier
            .clickable(
                onClickLabel = stringResource(R.string.note_open_transcript_at, label),
                onClick = onClick,
            )
            .padding(horizontal = TmSpacing.s, vertical = TmSpacing.xs),
    )
}

/** One point. Long-press opens the source sheet; a teal time opens the transcript at that line. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BulletRow(
    bullet: SummaryBullet,
    showSources: Boolean,
    onOpenTranscript: (String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.note_bullet_actions),
            )
            .padding(vertical = TmSpacing.xs + 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ProvenanceDot(
            color = if (showSources) sourceColor(bullet.source) else c.dim,
            modifier = Modifier.padding(top = 9.dp, end = TmSpacing.m),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(bullet.text, style = TrailMix.type.body, color = c.text)
            // Sub-points indent under the main point instead of a dash.
            bullet.details.forEach { detail ->
                Row(
                    modifier = Modifier.padding(start = TmSpacing.m, top = TmSpacing.xs),
                    verticalAlignment = Alignment.Top,
                ) {
                    ProvenanceDot(c.dim, Modifier.padding(top = 8.dp, end = TmSpacing.s), size = 4)
                    Text(detail, style = TrailMix.type.bodySmall, color = c.text)
                }
            }
        }
        val label = bullet.timestampLabel
        if (showSources && label != null) {
            TimestampLink(label, onClick = { onOpenTranscript(label) })
        }
    }
}

/** D2: a real checkbox. Done is struck through and dimmed but stays in place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NextStepRow(
    item: ActionItem,
    showSources: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenTranscript: (String) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    val full = item.copy(deadline = null).displayText()
    val ownerChars = item.owner?.trim()?.takeIf { it.isNotEmpty() }?.let { it.length + 1 } ?: 0
    val text = buildAnnotatedString {
        if (ownerChars > 0) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(full.take(ownerChars)) }
        }
        append(full.drop(ownerChars))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
                onLongClickLabel = stringResource(R.string.note_bullet_actions),
            ),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = item.done,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = full },
            colors = CheckboxDefaults.colors(
                checkedColor = c.text,
                checkmarkColor = c.background,
                uncheckedColor = c.outline,
            ),
        )
        Column(modifier = Modifier.weight(1f).padding(top = TmSpacing.m)) {
            Text(
                text = text,
                style = TrailMix.type.body.copy(
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                ),
                color = if (item.done) c.dim else c.text,
            )
            item.deadline?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(R.string.note_step_by, it.trim()),
                    style = TrailMix.type.caption,
                    color = c.dim,
                )
            }
        }
        val label = item.timestampLabel
        if (showSources && label != null) {
            TimestampLink(label, onClick = { onOpenTranscript(label) }, modifier = Modifier.padding(top = TmSpacing.s))
        }
    }
}

/**
 * The read view of a structured note: sections you can collapse (a real chevron), points with
 * provenance dots, and checkable Next Steps. Legacy highlights show as the first section.
 */
@Composable
internal fun StructuredNoteBody(
    summary: StructuredSummary,
    showSources: Boolean,
    enabled: Boolean,
    reordering: Boolean,
    onOpenTranscript: (String) -> Unit,
    onToggleStep: (Int, Boolean) -> Unit,
    onMoveSection: (Int, Int) -> Unit,
    onShowSource: (SourceSheetData) -> Unit,
) {
    val c = TrailMix.colors
    val shaped = remember(summary) { StructuredEdits.asSections(summary) }
    // asSections prepends a "Highlights" section; moves must address the stored order.
    val offset = if (summary.highlights.isNotEmpty()) 1 else 0
    Column {
        shaped.sections.forEachIndexed { index, section ->
            var expanded by remember(index, section.heading) { mutableStateOf(true) }
            val label = stringResource(
                if (expanded) R.string.note_collapse_section else R.string.note_expand_section,
                section.heading,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !reordering, onClickLabel = label) { expanded = !expanded }
                    .padding(top = TmSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.heading,
                    style = TrailMix.type.heading,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                if (reordering) {
                    val storedIndex = index - offset
                    val canUp = storedIndex > 0
                    val canDown = storedIndex in 0 until summary.sections.lastIndex
                    TmIconButton(
                        drawable = TmIcons.ArrowUp,
                        contentDescription = stringResource(R.string.note_move_up),
                        onClick = { onMoveSection(storedIndex, storedIndex - 1) },
                        enabled = canUp,
                    )
                    TmIconButton(
                        drawable = TmIcons.ArrowDown,
                        contentDescription = stringResource(R.string.note_move_down),
                        onClick = { onMoveSection(storedIndex, storedIndex + 1) },
                        enabled = canDown,
                    )
                } else {
                    TmIcon(
                        id = if (expanded) TmIcons.ArrowDown else TmIcons.ArrowUp,
                        contentDescription = null,
                        tint = c.dim,
                    )
                }
            }
            if (expanded && !reordering) {
                section.bullets.forEach { bullet ->
                    BulletRow(
                        bullet = bullet,
                        showSources = showSources,
                        onOpenTranscript = onOpenTranscript,
                        onLongPress = {
                            onShowSource(
                                SourceSheetData(
                                    text = bullet.text,
                                    source = bullet.source,
                                    excerpt = bullet.sourceExcerpt,
                                    timestampLabel = bullet.timestampLabel,
                                    edited = bullet.edited,
                                ),
                            )
                        },
                    )
                }
            }
        }

        if (summary.actionItems.isNotEmpty()) {
            Spacer(Modifier.size(TmSpacing.l))
            Text(
                text = stringResource(R.string.note_next_steps),
                style = TrailMix.type.overline,
                color = c.dim,
                modifier = Modifier.padding(bottom = TmSpacing.xs),
            )
            summary.actionItems.forEachIndexed { index, item ->
                NextStepRow(
                    item = item,
                    showSources = showSources,
                    enabled = enabled,
                    onToggle = { onToggleStep(index, it) },
                    onOpenTranscript = onOpenTranscript,
                    onLongPress = {
                        onShowSource(
                            SourceSheetData(
                                text = item.displayText(),
                                source = item.source,
                                excerpt = item.sourceExcerpt,
                                timestampLabel = item.timestampLabel,
                                edited = item.edited,
                            ),
                        )
                    },
                )
            }
        }
    }
}
