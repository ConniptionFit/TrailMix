package com.trailmix.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.ai.TranscriptCoverage
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.speech.CaptureJournal
import com.trailmix.app.data.speech.PendingJournal
import com.trailmix.app.ui.components.TmButton
import com.trailmix.app.ui.components.TmButtonIcon
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmOutlinedButton
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTonalButton
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** H1: what a brand-new install sees. Explains the product, the privacy promise and the two colors. */
@Composable
internal fun FirstRunContent(
    calendarGranted: Boolean,
    onStart: () -> Unit,
    onShowMeeting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = TmSpacing.l, vertical = TmSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.l),
    ) {
        Text(
            text = stringResource(R.string.home_first_overline).uppercase(),
            style = TrailMix.type.overline,
            color = c.dim,
        )
        Text(stringResource(R.string.home_first_title), style = TrailMix.type.display, color = c.text)

        Step(1, R.string.home_first_step1_title, R.string.home_first_step1_body)
        Step(2, R.string.home_first_step2_title, R.string.home_first_step2_body) {
            Row(
                modifier = Modifier.padding(top = TmSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(TmSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(c.amber, stringResource(R.string.home_first_legend_typed))
                LegendDot(c.teal, stringResource(R.string.home_first_legend_said))
            }
        }
        Step(3, R.string.home_first_step3_title, R.string.home_first_step3_body)

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(TmSpacing.m)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = c.dim, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.home_first_privacy), style = TrailMix.type.bodySmall, color = c.dim)
        }

        TmButton(
            label = stringResource(R.string.home_first_start),
            onClick = onStart,
            icon = TmButtonIcon.Drawable(TmIcons.Mic),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!calendarGranted) {
            TmOutlinedButton(
                label = stringResource(R.string.home_show_next_meeting),
                onClick = onShowMeeting,
                icon = TmButtonIcon.Drawable(TmIcons.Calendar),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Step(number: Int, title: Int, body: Int, extra: @Composable () -> Unit = {}) {
    val c = TrailMix.colors
    Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.m), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(28.dp).border(1.dp, c.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = TrailMix.type.mono, color = c.text)
        }
        Column {
            Text(stringResource(title), style = TrailMix.type.heading, color = c.text)
            Text(stringResource(body), style = TrailMix.type.bodySmall, color = c.dim)
            extra()
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(
            label,
            style = TrailMix.type.caption,
            color = TrailMix.colors.dim,
            modifier = Modifier.padding(start = TmSpacing.xs + 2.dp),
        )
    }
}

/** H2: the next calendar event with the two things you can do about it as real buttons. */
@Composable
internal fun UpcomingMeetingCard(
    meeting: UpcomingMeeting,
    onStart: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = TrailMix.colors
    val minutes = meeting.minutesUntilStart
    val soon = when {
        minutes <= 0 -> stringResource(R.string.home_next_now)
        minutes >= 120 -> stringResource(R.string.home_next_in_hours, (minutes / 60).toInt())
        else -> stringResource(R.string.home_next_in, minutes.toInt())
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(TmSpacing.l),
        verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Text(
            stringResource(R.string.home_next_label, soon, meeting.timeLabel),
            style = TrailMix.type.caption,
            color = c.dim,
        )
        Text(
            meeting.title,
            style = TrailMix.type.heading,
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TmSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            TmTonalButton(
                label = stringResource(R.string.home_start_note),
                onClick = onStart,
                icon = TmButtonIcon.Drawable(TmIcons.Mic),
            )
            TmTextButton(stringResource(R.string.home_see_meetings), onClick = onSeeAll)
        }
    }
}

/** The search box. Hits include transcript moments and typed lines (see [NoteListRow]). */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.home_search_hint),
) {
    val c = TrailMix.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(TrailMix.shapes.medium)
            .background(c.card)
            .padding(start = TmSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = c.dim, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).padding(horizontal = TmSpacing.m),
            textStyle = TrailMix.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.text),
            singleLine = true,
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = hint,
                        style = TrailMix.type.body,
                        color = c.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (query.isNotEmpty()) {
            TmIconButton(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.home_search_clear),
                onClick = { onQueryChange("") },
            )
        }
    }
}

/** B8: shown only when notes are behind on export, and says what to do about it. */
@Composable
internal fun ExportHealthLine(count: Int, repairing: Boolean, onExport: () -> Unit, modifier: Modifier = Modifier) {
    val c = TrailMix.colors
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
    ) {
        TmIcon(TmIcons.Folder, contentDescription = null, tint = c.dim, size = 20.dp)
        Text(
            text = pluralStringResource(R.plurals.home_export_behind, count, count),
            style = TrailMix.type.bodySmall,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        TmTextButton(
            label = stringResource(if (repairing) R.string.home_exporting else R.string.home_export_action),
            onClick = onExport,
            enabled = !repairing,
        )
    }
}

/** H2: "Today  3". The count is a quiet number, not a pill. */
@Composable
internal fun DayHeaderRow(header: HomeListItem.DayHeader) {
    val c = TrailMix.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TmSpacing.l, end = TmSpacing.l, top = TmSpacing.l, bottom = TmSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(header.label, style = TrailMix.type.overline, color = c.dim)
        Text(header.noteCount.toString(), style = TrailMix.type.mono, color = c.dim)
    }
}

/**
 * One note: title and a single meta line. A calendar glyph marks notes from a meeting. During a
 * search a result also says where it matched: a teal time opens the transcript at that line, an
 * amber "You" is a line you typed. [selected] is null outside selection mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteListRow(
    row: HomeNote,
    searching: Boolean,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMomentClick: (label: String) -> Unit,
) {
    val c = TrailMix.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = TmSpacing.l, vertical = TmSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                Box(
                    modifier = Modifier
                        .padding(end = TmSpacing.m)
                        .size(24.dp)
                        .clip(CircleShape)
                        .let {
                            if (selected) it.background(c.text) else it.border(1.5.dp, c.outline, CircleShape)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = c.background, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.note.title,
                    style = TrailMix.type.heading,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.note.meetingTitle != null) {
                        val linked = stringResource(R.string.home_row_calendar)
                        TmIcon(
                            TmIcons.Calendar,
                            contentDescription = null,
                            tint = c.dim,
                            size = 14.dp,
                            modifier = Modifier.padding(end = TmSpacing.xs).semantics { contentDescription = linked },
                        )
                    }
                    Text(
                        text = if (searching) row.metaWithDay else row.meta,
                        style = TrailMix.type.caption,
                        color = c.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val moment = row.matchedMoment
        val typed = row.matchedTyped
        if (moment != null) {
            MatchStrip(
                label = moment.label,
                labelColor = c.teal,
                mono = true,
                text = moment.text,
                onClick = { onMomentClick(moment.label) },
            )
        } else if (typed != null) {
            MatchStrip(
                label = stringResource(R.string.home_match_you),
                labelColor = c.amber,
                mono = false,
                text = typed,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun MatchStrip(label: String, labelColor: androidx.compose.ui.graphics.Color, mono: Boolean, text: String, onClick: () -> Unit) {
    val c = TrailMix.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TmSpacing.l)
            .padding(bottom = TmSpacing.s)
            .clip(TrailMix.shapes.small)
            .background(c.card)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = TmSpacing.m, vertical = TmSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
    ) {
        Text(
            text = label,
            style = if (mono) TrailMix.type.mono else TrailMix.type.caption,
            color = labelColor,
        )
        Text(
            text = text,
            style = TrailMix.type.bodySmall,
            color = c.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.dim)
    }
}

/** A1: the crash-recovery choice as a sheet with one clear default, instead of a four-button dialog. */
@Composable
internal fun RecoverySheet(
    pending: PendingJournal,
    onBuild: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onLater: () -> Unit,
) {
    val c = TrailMix.colors
    var confirmDiscard by remember { mutableStateOf(false) }

    if (confirmDiscard) {
        TmConfirmDialog(
            title = stringResource(R.string.home_recovery_discard_title),
            body = stringResource(R.string.home_recovery_discard_body),
            confirmLabel = stringResource(R.string.home_recovery_discard_confirm),
            dismissLabel = stringResource(R.string.home_recovery_discard_keep),
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onDiscard()
            },
            onDismiss = { confirmDiscard = false },
        )
    }

    // Dismissing the sheet is "Later", not a decision: the journal stays and is offered again.
    TmSheet(onDismiss = onLater, title = stringResource(R.string.home_recovery_title)) {
        Column(
            modifier = Modifier.padding(horizontal = TmSpacing.l),
            verticalArrangement = Arrangement.spacedBy(TmSpacing.m),
        ) {
            Text(stringResource(R.string.home_recovery_body), style = TrailMix.type.bodySmall, color = c.dim)
            Text(recoverySummary(pending.session), style = TrailMix.type.label, color = c.text)
            TmButton(
                label = stringResource(R.string.home_recovery_build),
                onClick = onBuild,
                modifier = Modifier.fillMaxWidth(),
            )
            TmTonalButton(
                label = stringResource(R.string.home_recovery_keep),
                onClick = onKeep,
                icon = TmButtonIcon.Drawable(TmIcons.Mic),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TmTextButton(
                    label = stringResource(R.string.home_recovery_discard),
                    onClick = { confirmDiscard = true },
                    destructive = true,
                )
                TmTextButton(stringResource(R.string.home_recovery_later), onClick = onLater)
            }
        }
    }
}

/** e.g. "Jul 31, 3:04 PM · 42:15 · 318 transcript lines · your typed notes". */
@Composable
private fun recoverySummary(session: CaptureJournal.RecoveredSession): String {
    val parts = mutableListOf<String>()
    if (session.startedAtEpochMs > 0) {
        parts += SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(session.startedAtEpochMs)) +
            ", " + timeLabel(session.startedAtEpochMs)
    }
    if (session.durationMs > 0) {
        parts += TranscriptCoverage.formatSeconds((session.durationMs / 1000).toInt())
    }
    val lines = session.transcript.size
    if (lines > 0) parts += pluralStringResource(R.plurals.home_recovery_lines, lines, lines)
    if (session.typedFragments.isNotBlank()) parts += stringResource(R.string.home_recovery_typed)
    return parts.joinToString(" · ")
}

/**
 * Confirmation for starting a capture ahead of a not-yet-imminent meeting (more than five
 * minutes out). Shared by Home and the meetings list.
 */
@Composable
fun StartCaptureDialog(
    meeting: UpcomingMeeting,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    TmConfirmDialog(
        title = meeting.title,
        body = stringResource(R.string.home_start_now_body, meeting.minutesUntilStart.toInt(), meeting.timeLabel),
        confirmLabel = stringResource(R.string.home_start_now),
        dismissLabel = stringResource(R.string.home_start_wait),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
