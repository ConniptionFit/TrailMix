package com.trailmix.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.trailmix.app.R
import com.trailmix.app.data.model.CustomSummaryTemplate
import com.trailmix.app.data.model.SectionSpec
import com.trailmix.app.data.model.UserProfile
import com.trailmix.app.ui.components.TmCloseButton
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmIconButton
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmOutlinedButton
import com.trailmix.app.ui.components.TmTextButton
import com.trailmix.app.ui.components.TmTextField
import com.trailmix.app.ui.components.TmTopBar
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix

/**
 * One full-screen editor shell for every Settings form (recipes, templates, profile,
 * vocabulary). The old dialogs were 480dp-high boxes that clipped long content and fought the
 * keyboard; a full screen scrolls, resizes with the keyboard, and keeps Save in the top bar.
 * Close is [onClose]; pass a null [saveLabel] for a read-only viewer.
 */
@Composable
internal fun FullScreenEditor(
    title: String,
    onClose: () -> Unit,
    saveLabel: String?,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = TrailMix.colors.background) {
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TmTopBar(
                    title = title,
                    navigation = { TmCloseButton(onClick = onClose, contentDescription = stringResource(R.string.settings_close)) },
                    actions = {
                        if (saveLabel != null) TmTextButton(saveLabel, onClick = onSave, enabled = saveEnabled)
                    },
                )
                Column(
                    modifier = Modifier
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = TmSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(TmSpacing.l),
                ) {
                    content()
                }
            }
        }
    }
}

/** Delete with a confirm, shared by every editor that can remove what it edits. */
@Composable
private fun DeleteButton(label: String, name: String, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        TmConfirmDialog(
            title = stringResource(R.string.settings_delete_title, name),
            body = stringResource(R.string.settings_delete_body),
            confirmLabel = stringResource(R.string.settings_delete_confirm),
            destructive = true,
            onConfirm = {
                confirming = false
                onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
    TmTextButton(label, onClick = { confirming = true }, destructive = true)
}

/** Name plus free text: custom recipes. */
@Composable
internal fun RecipeEditor(
    initialName: String,
    initialText: String,
    onSave: (name: String, text: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    FullScreenEditor(
        title = stringResource(if (initialName.isBlank()) R.string.settings_recipe_new else R.string.settings_recipe_edit),
        onClose = onDismiss,
        saveLabel = stringResource(R.string.settings_save),
        saveEnabled = name.isNotBlank() && text.isNotBlank(),
        onSave = { onSave(name, text) },
    ) {
        TmTextField(stringResource(R.string.settings_field_name), name, { name = it }, hint = stringResource(R.string.settings_recipe_name_hint))
        TmTextField(
            label = stringResource(R.string.settings_recipe_prompt),
            value = text,
            onValueChange = { text = it },
            hint = stringResource(R.string.settings_recipe_prompt_hint),
            singleLine = false,
            minHeight = 160,
        )
        onDelete?.let { DeleteButton(stringResource(R.string.settings_recipe_delete), initialName, it) }
    }
}

/**
 * AI-19: a custom summary template: name, meeting context, and ordered sections. Sections
 * reorder with up and down buttons; "Next Steps" is always added, so it is not entered here.
 */
@Composable
internal fun TemplateEditor(
    initial: CustomSummaryTemplate,
    onSave: (name: String, context: String, sections: List<SectionSpec>) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var context by remember { mutableStateOf(initial.guidance) }
    val sections = remember { initial.sections.toMutableStateList() }
    FullScreenEditor(
        title = stringResource(if (initial.name.isBlank()) R.string.settings_template_new else R.string.settings_template_edit),
        onClose = onDismiss,
        saveLabel = stringResource(R.string.settings_save),
        saveEnabled = name.isNotBlank() && context.isNotBlank(),
        onSave = { onSave(name, context, sections.toList()) },
    ) {
        TmTextField(stringResource(R.string.settings_field_name), name, { name = it }, hint = stringResource(R.string.settings_template_name_hint))
        TmTextField(
            label = stringResource(R.string.settings_template_context),
            value = context,
            onValueChange = { context = it },
            hint = stringResource(R.string.settings_template_context_hint),
            singleLine = false,
            minHeight = 112,
        )
        Column(verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
            Text(stringResource(R.string.settings_template_sections).uppercase(), style = TrailMix.type.overline, color = TrailMix.colors.dim)
            Text(stringResource(R.string.settings_template_sections_help), style = TrailMix.type.bodySmall, color = TrailMix.colors.dim)
        }
        sections.forEachIndexed { i, section ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TrailMix.colors.card, TrailMix.shapes.medium)
                    .padding(TmSpacing.m),
                verticalArrangement = Arrangement.spacedBy(TmSpacing.s),
            ) {
                TmTextField(
                    label = stringResource(R.string.settings_section_heading, i + 1),
                    value = section.heading,
                    onValueChange = { sections[i] = section.copy(heading = it) },
                    hint = stringResource(R.string.settings_section_heading_hint),
                )
                TmTextField(
                    label = stringResource(R.string.settings_section_instruction),
                    value = section.instruction,
                    onValueChange = { sections[i] = section.copy(instruction = it) },
                    hint = stringResource(R.string.settings_section_instruction_hint),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TmIconButton(
                        drawable = TmIcons.ArrowUp,
                        contentDescription = stringResource(R.string.settings_section_up),
                        enabled = i > 0,
                        onClick = {
                            val above = sections[i - 1]
                            sections[i - 1] = sections[i]
                            sections[i] = above
                        },
                    )
                    TmIconButton(
                        drawable = TmIcons.ArrowDown,
                        contentDescription = stringResource(R.string.settings_section_down),
                        enabled = i < sections.lastIndex,
                        onClick = {
                            val below = sections[i + 1]
                            sections[i + 1] = sections[i]
                            sections[i] = below
                        },
                    )
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TmTextButton(
                            label = stringResource(R.string.settings_section_remove),
                            onClick = { sections.removeAt(i) },
                            destructive = true,
                        )
                    }
                }
            }
        }
        TmOutlinedButton(
            label = stringResource(R.string.settings_section_add),
            onClick = { sections.add(SectionSpec("", "")) },
        )
        onDelete?.let { DeleteButton(stringResource(R.string.settings_template_delete), initial.name, it) }
    }
}

/** AI-21: name, role, company and focus areas (comma-separated text). */
@Composable
internal fun ProfileEditor(
    initial: UserProfile,
    onSave: (UserProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var role by remember { mutableStateOf(initial.role) }
    var company by remember { mutableStateOf(initial.company) }
    var focus by remember { mutableStateOf(initial.focusAreas.joinToString(", ")) }
    FullScreenEditor(
        title = stringResource(R.string.settings_profile_title),
        onClose = onDismiss,
        saveLabel = stringResource(R.string.settings_save),
        saveEnabled = true,
        onSave = { onSave(UserProfile(name, role, company, UserProfile.parseFocusAreas(focus))) },
    ) {
        Text(stringResource(R.string.settings_profile_help), style = TrailMix.type.bodySmall, color = TrailMix.colors.dim)
        TmTextField(stringResource(R.string.settings_profile_name), name, { name = it.take(UserProfile.MAX_NAME) }, hint = stringResource(R.string.settings_profile_name_hint))
        TmTextField(stringResource(R.string.settings_profile_role), role, { role = it.take(UserProfile.MAX_ROLE) }, hint = stringResource(R.string.settings_profile_role_hint))
        TmTextField(stringResource(R.string.settings_profile_company), company, { company = it.take(UserProfile.MAX_COMPANY) }, hint = stringResource(R.string.settings_profile_company_hint))
        TmTextField(
            label = stringResource(R.string.settings_profile_focus),
            value = focus,
            onValueChange = { focus = it.take(UserProfile.MAX_FOCUS * UserProfile.MAX_FOCUS_AREAS) },
            hint = stringResource(R.string.settings_profile_focus_hint),
            singleLine = false,
            minHeight = 96,
        )
    }
}

/** AI-08: a wrong/right pair, two short single-line fields. */
@Composable
internal fun VocabularyEditor(
    initialWrong: String,
    initialCorrect: String,
    onSave: (wrong: String, correct: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var wrong by remember { mutableStateOf(initialWrong) }
    var correct by remember { mutableStateOf(initialCorrect) }
    FullScreenEditor(
        title = stringResource(if (initialWrong.isBlank()) R.string.settings_vocab_new else R.string.settings_vocab_edit),
        onClose = onDismiss,
        saveLabel = stringResource(R.string.settings_save),
        saveEnabled = wrong.isNotBlank() && correct.isNotBlank(),
        onSave = { onSave(wrong, correct) },
    ) {
        TmTextField(stringResource(R.string.settings_vocab_wrong), wrong, { wrong = it }, hint = stringResource(R.string.settings_vocab_wrong_hint))
        TmTextField(stringResource(R.string.settings_vocab_right), correct, { correct = it }, hint = stringResource(R.string.settings_vocab_right_hint))
        onDelete?.let { DeleteButton(stringResource(R.string.settings_vocab_delete), initialWrong, it) }
    }
}

/** Read-only view of what a built-in recipe or template tells the AI. Custom ones offer Edit. */
@Composable
internal fun PromptViewer(
    title: String,
    kind: String,
    body: String,
    footer: String,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    FullScreenEditor(
        title = title,
        onClose = onDismiss,
        saveLabel = if (onEdit != null) stringResource(R.string.settings_edit) else null,
        saveEnabled = true,
        onSave = { onEdit?.invoke() },
    ) {
        Text(kind.uppercase(), style = TrailMix.type.overline, color = TrailMix.colors.dim)
        Text(
            text = body,
            style = TrailMix.type.body,
            color = TrailMix.colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(TrailMix.colors.card, TrailMix.shapes.medium)
                .padding(TmSpacing.m),
        )
        Text(footer, style = TrailMix.type.bodySmall, color = TrailMix.colors.dim)
    }
}
