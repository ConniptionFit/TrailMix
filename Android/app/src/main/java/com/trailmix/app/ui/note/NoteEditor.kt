package com.trailmix.app.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.model.Provenance
import com.trailmix.app.data.model.StructuredEdits
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * N4: edit the note in its structure. Every heading, point and next step is its own field.
 * Enter starts a new point; Backspace on an empty point removes it. Spoken points stay teal
 * and are labelled "edited by you" once changed; new points are yours (amber).
 */
@Composable
internal fun StructureEditor(
    title: String,
    onTitleChange: (String) -> Unit,
    summary: StructuredSummary,
    onSummaryChange: (StructuredSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    // The (section, bullet) that should take focus after an add.
    var focusTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TmSpacing.m)) {
        EditField(
            value = title,
            onChange = onTitleChange,
            hint = stringResource(R.string.note_edit_title_hint),
            style = TrailMix.type.heading,
        )

        summary.sections.forEachIndexed { si, section ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditField(
                    value = section.heading,
                    onChange = { onSummaryChange(StructuredEdits.renameSection(summary, si, it)) },
                    hint = stringResource(R.string.note_edit_heading_hint),
                    style = TrailMix.type.heading,
                    modifier = Modifier.weight(1f),
                )
                TmIconButton(
                    drawable = TmIcons.ArrowUp,
                    contentDescription = stringResource(R.string.note_move_up),
                    onClick = { onSummaryChange(summary.moveSectionTo(si, si - 1)) },
                    enabled = si > 0,
                )
                TmIconButton(
                    drawable = TmIcons.ArrowDown,
                    contentDescription = stringResource(R.string.note_move_down),
                    onClick = { onSummaryChange(summary.moveSectionTo(si, si + 1)) },
                    enabled = si < summary.sections.lastIndex,
                )
                TmIconButton(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.note_edit_remove),
                    onClick = { onSummaryChange(StructuredEdits.removeSection(summary, si)) },
                )
            }

            section.bullets.forEachIndexed { bi, bullet ->
                val requester = remember { FocusRequester() }
                LaunchedEffect(focusTarget) {
                    if (focusTarget == si to bi) {
                        runCatching { requester.requestFocus() }
                        focusTarget = null
                    }
                }
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        EditField(
                            value = bullet.text,
                            onChange = { onSummaryChange(StructuredEdits.editBullet(summary, si, bi, it)) },
                            hint = stringResource(R.string.note_edit_point_hint),
                            focusRequester = requester,
                            onEnter = {
                                val added = StructuredEdits.addBullet(summary, si)
                                val last = added.sections[si].bullets.lastIndex
                                onSummaryChange(StructuredEdits.moveBullet(added, si, last, bi + 1))
                                focusTarget = si to bi + 1
                            },
                            onBackspaceEmpty = {
                                onSummaryChange(StructuredEdits.removeBullet(summary, si, bi))
                                if (bi > 0) focusTarget = si to bi - 1
                            },
                        )
                        provenanceCaption(bullet.source, bullet.timestampLabel, bullet.edited)?.let {
                            Text(
                                text = it,
                                style = TrailMix.type.caption,
                                color = sourceColor(bullet.source),
                                modifier = Modifier.padding(start = TmSpacing.m, top = TmSpacing.xs),
                            )
                        }
                    }
                    TmIconButton(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.note_edit_remove),
                        onClick = { onSummaryChange(StructuredEdits.removeBullet(summary, si, bi)) },
                    )
                }
            }
            TmTextButton(
                label = stringResource(R.string.note_edit_add_point),
                onClick = {
                    onSummaryChange(StructuredEdits.addBullet(summary, si))
                    focusTarget = si to section.bullets.size
                },
            )
        }

        TmTextButton(
            label = stringResource(R.string.note_edit_add_section),
            onClick = { onSummaryChange(StructuredEdits.addSection(summary)) },
        )

        Text(
            text = stringResource(R.string.note_next_steps),
            style = TrailMix.type.overline,
            color = c.dim,
        )
        summary.actionItems.forEachIndexed { ai, item ->
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
                    EditField(
                        value = item.owner.orEmpty(),
                        onChange = {
                            onSummaryChange(StructuredEdits.editAction(summary, ai, item.text, it, item.deadline))
                        },
                        hint = stringResource(R.string.note_edit_owner_hint),
                        style = TrailMix.type.label,
                    )
                    EditField(
                        value = item.text,
                        onChange = {
                            onSummaryChange(StructuredEdits.editAction(summary, ai, it, item.owner, item.deadline))
                        },
                        hint = stringResource(R.string.note_edit_step_hint),
                    )
                    EditField(
                        value = item.deadline.orEmpty(),
                        onChange = {
                            onSummaryChange(StructuredEdits.editAction(summary, ai, item.text, item.owner, it))
                        },
                        hint = stringResource(R.string.note_edit_deadline_hint),
                        style = TrailMix.type.bodySmall,
                    )
                }
                TmIconButton(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.note_edit_remove),
                    onClick = { onSummaryChange(StructuredEdits.removeAction(summary, ai)) },
                )
            }
        }
        TmTextButton(
            label = stringResource(R.string.note_edit_add_step),
            onClick = { onSummaryChange(StructuredEdits.addAction(summary)) },
        )
    }
}

private fun StructuredSummary.moveSectionTo(from: Int, to: Int): StructuredSummary {
    if (from == to || from !in sections.indices || to !in sections.indices) return this
    val list = sections.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(sections = list)
}

/** "From the recording · 11:56 · edited by you" for spoken points; typed points get no caption. */
@Composable
private fun provenanceCaption(source: Provenance, label: String?, edited: Boolean): String? {
    if (source == Provenance.FRAGMENT) return null
    val time = label ?: return if (edited) stringResource(R.string.note_edit_edited_by_you) else null
    return stringResource(
        if (edited) R.string.note_sheet_recording_edited else R.string.note_sheet_recording,
        time,
    )
}

/** A card-backed text field with a hint. [onEnter] turns the Enter key into "next point". */
@Composable
private fun EditField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TrailMix.type.body,
    focusRequester: FocusRequester? = null,
    onEnter: (() -> Unit)? = null,
    onBackspaceEmpty: (() -> Unit)? = null,
) {
    val c = TrailMix.colors
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (onEnter != null && next.contains('\n')) {
                onChange(next.replace("\n", ""))
                onEnter()
            } else {
                onChange(next)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onPreviewKeyEvent { event ->
                if (onBackspaceEmpty != null && value.isEmpty() &&
                    event.key == Key.Backspace && event.type == KeyEventType.KeyDown
                ) {
                    onBackspaceEmpty()
                    true
                } else {
                    false
                }
            },
        textStyle = style.copy(color = c.text),
        cursorBrush = SolidColor(c.text),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TrailMix.shapes.small)
                    .background(c.card)
                    .padding(horizontal = TmSpacing.m, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(hint, style = style, color = c.dim)
                inner()
            }
        },
    )
}
