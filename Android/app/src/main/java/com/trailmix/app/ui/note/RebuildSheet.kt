package com.trailmix.app.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trailmix.app.R
import com.trailmix.app.data.model.TemplateOption
import com.trailmix.app.ui.components.TmButton
import com.trailmix.app.ui.components.TmListRow
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

private const val COLLAPSED_TEMPLATES = 5

/**
 * N5: one sheet replaces the two stock dialogs ("Regenerate with template" and "Replace your
 * edits?"). The warning and the button verb only appear when the note has [edits] to replace.
 */
@Composable
internal fun RebuildSheet(
    templates: List<TemplateOption>,
    currentStored: String,
    edits: Int,
    onDismiss: () -> Unit,
    onRebuild: (String) -> Unit,
) {
    val c = TrailMix.colors
    var selected by remember { mutableStateOf(currentStored) }
    var showAll by remember { mutableStateOf(false) }
    val visible = if (showAll || templates.size <= COLLAPSED_TEMPLATES + 1) {
        templates
    } else {
        // The first few, plus the current one if it would otherwise be hidden.
        templates.take(COLLAPSED_TEMPLATES) +
            templates.drop(COLLAPSED_TEMPLATES).filter { it.stored == currentStored }
    }

    TmSheet(onDismiss = onDismiss, title = stringResource(R.string.note_rebuild_title)) {
        Column(modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Column(
                modifier = Modifier.padding(horizontal = TmSpacing.l),
                verticalArrangement = Arrangement.spacedBy(TmSpacing.m),
            ) {
                Text(
                    stringResource(R.string.note_rebuild_body),
                    style = TrailMix.type.bodySmall,
                    color = c.dim,
                )
                if (edits > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(TrailMix.shapes.medium)
                            .background(c.errorContainer)
                            .padding(TmSpacing.m),
                        horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = c.text)
                        Text(
                            pluralStringResource(R.plurals.note_rebuild_warning, edits, edits),
                            style = TrailMix.type.bodySmall,
                            color = c.text,
                        )
                    }
                }
                Text(
                    stringResource(R.string.note_rebuild_template),
                    style = TrailMix.type.overline,
                    color = c.dim,
                )
            }
            visible.forEach { option ->
                TmListRow(
                    title = option.label,
                    subtitle = if (option.stored == currentStored) {
                        stringResource(R.string.note_rebuild_current)
                    } else {
                        option.guidance.lineSequence().firstOrNull().orEmpty().take(90).ifBlank { null }
                    },
                    leading = {
                        RadioButton(
                            selected = option.stored == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = c.text,
                                unselectedColor = c.outline,
                            ),
                        )
                    },
                    onClick = { selected = option.stored },
                )
            }
            if (templates.size > COLLAPSED_TEMPLATES + 1) {
                TmTextButton(
                    label = if (showAll) {
                        stringResource(R.string.note_rebuild_fewer)
                    } else {
                        stringResource(R.string.note_rebuild_all, templates.size)
                    },
                    onClick = { showAll = !showAll },
                    modifier = Modifier.padding(horizontal = TmSpacing.s),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(TmSpacing.m, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TmTextButton(stringResource(R.string.note_cancel), onClick = onDismiss)
            TmButton(
                label = stringResource(
                    if (edits > 0) R.string.note_rebuild_go_replace else R.string.note_rebuild_go,
                ),
                onClick = { onRebuild(selected) },
            )
        }
    }
}
