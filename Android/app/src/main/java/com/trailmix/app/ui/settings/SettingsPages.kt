package com.trailmix.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.R
import com.trailmix.app.data.ai.DEFAULT_RECIPES
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.ai.VocabularyTerm
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.CustomSummaryTemplate
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TemplateOptions
import com.trailmix.app.data.settings.ThemeMode
import com.trailmix.app.data.speech.AsrLocales
import com.trailmix.app.ui.components.TmConfirmDialog
import com.trailmix.app.ui.components.TmIcon
import com.trailmix.app.ui.components.TmIcons
import com.trailmix.app.ui.components.TmListRow
import com.trailmix.app.ui.components.TmNavRow
import com.trailmix.app.ui.components.TmOutlinedButton
import com.trailmix.app.ui.components.TmSegmented
import com.trailmix.app.ui.components.TmSheet
import com.trailmix.app.ui.components.TmSwitchRow
import com.trailmix.app.ui.components.TmTonalButton
import com.trailmix.app.ui.export.label
import com.trailmix.app.ui.theme.TmSpacing
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.launch

/** The six Settings groups, ordered by how often you touch them (G1). */
enum class SettingsPage { ROOT, APPEARANCE, CAPTURE, NOTES, EXPORT, PRIVACY, ABOUT }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = TrailMix.type.overline,
        color = TrailMix.colors.dim,
        modifier = Modifier.padding(start = TmSpacing.l, end = TmSpacing.l, top = TmSpacing.xl, bottom = TmSpacing.xs),
    )
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = TrailMix.type.bodySmall,
        color = TrailMix.colors.dim,
        modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xs),
    )
}

/** One option of a single-choice list: the current one carries a check and is announced as selected. */
@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    TmListRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected },
        trailing = {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = TrailMix.colors.text)
        },
    )
}

/** A scrolling single-choice sheet, for lists too long to show inline. */
@Composable
private fun ChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    TmSheet(onDismiss = onDismiss, title = title) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                ChoiceRow(title = label, selected = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = TmSpacing.xxl)) {
        content()
    }
}

// ── S1: top level ───────────────────────────────────────────────────────────

@Composable
internal fun SettingsRoot(viewModel: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val asrLocaleTag by viewModel.asrLocaleTag.collectAsStateWithLifecycle()
    val vocabulary by viewModel.vocabularyTerms.collectAsStateWithLifecycle()
    val diarization by viewModel.speakerDiarizationEnabled.collectAsStateWithLifecycle()
    val defaultTemplate by viewModel.defaultTemplate.collectAsStateWithLifecycle()
    val customTemplates by viewModel.customTemplates.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val exportName by viewModel.exportLocationName.collectAsStateWithLifecycle()
    val unexported by viewModel.unexportedCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val c = TrailMix.colors
    PageColumn {
        TmNavRow(
            title = stringResource(R.string.settings_appearance),
            subtitle = stringResource(themeLabel(themeMode)),
            leading = { TmIcon(TmIcons.Visibility, null) },
            onClick = { onOpen(SettingsPage.APPEARANCE) },
        )
        TmNavRow(
            title = stringResource(R.string.settings_capture),
            subtitle = buildList {
                add(AsrLocales.fromTag(asrLocaleTag).label)
                if (diarization) add(stringResource(R.string.settings_capture_speakers_on))
                if (vocabulary.isNotEmpty()) add(pluralStringResource(R.plurals.settings_vocab_count, vocabulary.size, vocabulary.size))
            }.joinToString(" · "),
            leading = { TmIcon(TmIcons.Mic, null) },
            onClick = { onOpen(SettingsPage.CAPTURE) },
        )
        TmNavRow(
            title = stringResource(R.string.settings_notes),
            subtitle = listOf(
                TemplateOptions.all(customTemplates).firstOrNull { it.stored == defaultTemplate }?.label
                    ?: SummaryTemplate.AUTO.label,
                stringResource(if (userProfile.isEmpty) R.string.settings_notes_no_profile else R.string.settings_notes_profile),
            ).joinToString(" · "),
            leading = { TmIcon(TmIcons.Subject, null) },
            onClick = { onOpen(SettingsPage.NOTES) },
        )
        TmNavRow(
            title = stringResource(R.string.settings_export),
            subtitle = when {
                exportName == null -> stringResource(R.string.settings_export_none)
                unexported > 0 -> pluralStringResource(R.plurals.settings_export_behind, unexported, unexported)
                else -> exportName.orEmpty()
            },
            leading = { TmIcon(TmIcons.Folder, null) },
            onClick = { onOpen(SettingsPage.EXPORT) },
        )
        TmNavRow(
            title = stringResource(R.string.settings_privacy),
            subtitle = stringResource(R.string.settings_privacy_summary),
            leading = { Icon(Icons.Filled.Lock, contentDescription = null, tint = c.text) },
            onClick = { onOpen(SettingsPage.PRIVACY) },
        )
        TmNavRow(
            title = stringResource(R.string.settings_about),
            subtitle = stringResource(R.string.settings_about_version, versionName(context)),
            leading = { Icon(Icons.Filled.Info, contentDescription = null, tint = c.text) },
            onClick = { onOpen(SettingsPage.ABOUT) },
        )
        Row(
            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(TmSpacing.m),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = c.dim, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.settings_footer), style = TrailMix.type.bodySmall, color = c.dim)
        }
    }
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun versionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull().orEmpty()

// ── S2: Appearance ──────────────────────────────────────────────────────────

@Composable
internal fun AppearancePage(viewModel: SettingsViewModel, systemDark: Boolean) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    PageColumn {
        SectionHeader(stringResource(R.string.settings_theme))
        TmSegmented(
            options = ThemeMode.entries.map { it to stringResource(themeLabel(it)) },
            selected = themeMode,
            onSelect = viewModel::setThemeMode,
            modifier = Modifier.fillMaxWidth().padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        )
        HelpText(
            stringResource(
                when (themeMode) {
                    ThemeMode.SYSTEM -> if (systemDark) R.string.settings_theme_help_system_dark else R.string.settings_theme_help_system_light
                    ThemeMode.LIGHT -> R.string.settings_theme_help_light
                    ThemeMode.DARK -> R.string.settings_theme_help_dark
                },
            ),
        )
        SectionHeader(stringResource(R.string.settings_text))
        HelpText(stringResource(R.string.settings_text_help))
    }
}

// ── Capture & speech ────────────────────────────────────────────────────────

@Composable
internal fun CapturePage(viewModel: SettingsViewModel) {
    val asrLocaleTag by viewModel.asrLocaleTag.collectAsStateWithLifecycle()
    val diarization by viewModel.speakerDiarizationEnabled.collectAsStateWithLifecycle()
    val vocabulary by viewModel.vocabularyTerms.collectAsStateWithLifecycle()
    var choosingLanguage by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VocabularyTerm?>(null) }

    if (choosingLanguage) {
        ChoiceSheet(
            title = stringResource(R.string.settings_language),
            options = AsrLocales.options.map { it.tag to it.label },
            selected = AsrLocales.fromTag(asrLocaleTag).tag,
            onSelect = {
                viewModel.setAsrLocale(it)
                choosingLanguage = false
            },
            onDismiss = { choosingLanguage = false },
        )
    }
    editing?.let { term ->
        VocabularyEditor(
            initialWrong = term.wrong,
            initialCorrect = term.correct,
            onSave = { wrong, correct ->
                viewModel.saveVocabularyTerm(wrong, correct, originalWrong = term.wrong.ifBlank { null })
                editing = null
            },
            onDelete = if (term.wrong.isNotBlank()) {
                {
                    viewModel.deleteVocabularyTerm(term.wrong)
                    editing = null
                }
            } else {
                null
            },
            onDismiss = { editing = null },
        )
    }

    PageColumn {
        SectionHeader(stringResource(R.string.settings_language))
        TmNavRow(
            title = stringResource(R.string.settings_language_row),
            subtitle = AsrLocales.fromTag(asrLocaleTag).label,
            onClick = { choosingLanguage = true },
        )
        HelpText(stringResource(R.string.settings_language_help))

        SectionHeader(stringResource(R.string.settings_speakers))
        TmSwitchRow(
            title = stringResource(R.string.settings_speakers_title),
            subtitle = stringResource(R.string.settings_speakers_body),
            checked = diarization,
            onCheckedChange = viewModel::setSpeakerDiarizationEnabled,
        )

        SectionHeader(stringResource(R.string.settings_vocab))
        HelpText(stringResource(R.string.settings_vocab_help))
        vocabulary.forEach { term ->
            TmNavRow(title = term.wrong, subtitle = stringResource(R.string.settings_vocab_arrow, term.correct), onClick = { editing = term })
        }
        TmOutlinedButton(
            label = stringResource(R.string.settings_vocab_add),
            onClick = { editing = VocabularyTerm("", "") },
            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        )
    }
}

// ── Notes & AI ──────────────────────────────────────────────────────────────

@Composable
internal fun NotesPage(viewModel: SettingsViewModel) {
    val defaultTemplate by viewModel.defaultTemplate.collectAsStateWithLifecycle()
    val customTemplates by viewModel.customTemplates.collectAsStateWithLifecycle()
    val customRecipes by viewModel.customRecipes.collectAsStateWithLifecycle()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()

    var choosingTemplate by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var viewingRecipe by remember { mutableStateOf<Pair<Recipe, Boolean>?>(null) }
    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }
    var viewingTemplate by remember { mutableStateOf<Pair<CustomSummaryTemplate, Boolean>?>(null) }
    var editingTemplate by remember { mutableStateOf<CustomSummaryTemplate?>(null) }

    val templateOptions = TemplateOptions.all(customTemplates)
    if (choosingTemplate) {
        ChoiceSheet(
            title = stringResource(R.string.settings_default_template),
            options = templateOptions.map { it.stored to it.label },
            selected = defaultTemplate,
            onSelect = {
                viewModel.setDefaultTemplate(it)
                choosingTemplate = false
            },
            onDismiss = { choosingTemplate = false },
        )
    }
    if (editingProfile) {
        ProfileEditor(
            initial = userProfile,
            onSave = {
                viewModel.saveUserProfile(it)
                editingProfile = false
            },
            onDismiss = { editingProfile = false },
        )
    }
    viewingRecipe?.let { (recipe, custom) ->
        PromptViewer(
            title = recipe.name,
            kind = stringResource(if (custom) R.string.settings_recipe_kind_custom else R.string.settings_recipe_kind_builtin),
            body = recipe.prompt,
            footer = stringResource(R.string.settings_recipe_footer),
            onEdit = if (custom) {
                {
                    viewingRecipe = null
                    editingRecipe = recipe
                }
            } else {
                null
            },
            onDismiss = { viewingRecipe = null },
        )
    }
    editingRecipe?.let { recipe ->
        RecipeEditor(
            initialName = recipe.name,
            initialText = recipe.prompt,
            onSave = { name, prompt ->
                viewModel.saveRecipe(name, prompt, originalName = recipe.name.ifBlank { null })
                editingRecipe = null
            },
            onDelete = if (recipe.name.isNotBlank()) {
                {
                    viewModel.deleteRecipe(recipe.name)
                    editingRecipe = null
                }
            } else {
                null
            },
            onDismiss = { editingRecipe = null },
        )
    }
    viewingTemplate?.let { (template, custom) ->
        PromptViewer(
            title = template.name,
            kind = stringResource(if (custom) R.string.settings_template_kind_custom else R.string.settings_template_kind_builtin),
            body = template.spec.describe(),
            footer = stringResource(R.string.settings_template_footer),
            onEdit = if (custom) {
                {
                    viewingTemplate = null
                    editingTemplate = template
                }
            } else {
                null
            },
            onDismiss = { viewingTemplate = null },
        )
    }
    editingTemplate?.let { template ->
        TemplateEditor(
            initial = template,
            onSave = { name, context, sections ->
                viewModel.saveTemplate(name, context, sections, originalName = template.name.ifBlank { null })
                editingTemplate = null
            },
            onDelete = if (template.name.isNotBlank()) {
                {
                    viewModel.deleteTemplate(template.name)
                    editingTemplate = null
                }
            } else {
                null
            },
            onDismiss = { editingTemplate = null },
        )
    }

    val autoDescription = stringResource(R.string.settings_auto_description)
    PageColumn {
        SectionHeader(stringResource(R.string.settings_default_template))
        TmNavRow(
            title = stringResource(R.string.settings_default_template_row),
            subtitle = templateOptions.firstOrNull { it.stored == defaultTemplate }?.label ?: SummaryTemplate.AUTO.label,
            onClick = { choosingTemplate = true },
        )
        HelpText(stringResource(R.string.settings_default_template_help))

        SectionHeader(stringResource(R.string.settings_profile))
        HelpText(stringResource(R.string.settings_profile_help))
        TmNavRow(
            title = if (userProfile.isEmpty) {
                stringResource(R.string.settings_profile_set)
            } else {
                userProfile.name.ifBlank { stringResource(R.string.settings_profile_title) }
            },
            subtitle = if (userProfile.isEmpty) {
                stringResource(R.string.settings_profile_empty)
            } else {
                listOf(
                    listOf(userProfile.role, userProfile.company).filter { it.isNotBlank() }.joinToString(" · "),
                    userProfile.focusAreas.joinToString(", "),
                ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { stringResource(R.string.settings_profile_nothing) }
            },
            onClick = { editingProfile = true },
        )

        SectionHeader(stringResource(R.string.settings_templates))
        HelpText(stringResource(R.string.settings_templates_help))
        SummaryTemplate.entries.forEach { template ->
            // AI-20: Auto has no guidance of its own; it picks one of the others per meeting.
            val viewable = if (template == SummaryTemplate.AUTO) {
                CustomSummaryTemplate(template.label, autoDescription)
            } else {
                CustomSummaryTemplate(template.label, template.guidance, template.sections)
            }
            TmNavRow(title = template.label, subtitle = viewable.guidance, onClick = { viewingTemplate = viewable to false })
        }
        customTemplates.forEach { template ->
            TmNavRow(title = template.name, subtitle = template.guidance, onClick = { viewingTemplate = template to true })
        }
        TmOutlinedButton(
            label = stringResource(R.string.settings_template_add),
            onClick = { editingTemplate = CustomSummaryTemplate("", "") },
            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        )

        SectionHeader(stringResource(R.string.settings_recipes))
        HelpText(stringResource(R.string.settings_recipes_help))
        DEFAULT_RECIPES.forEach { recipe ->
            TmNavRow(title = recipe.name, subtitle = recipe.prompt, onClick = { viewingRecipe = recipe to false })
        }
        customRecipes.forEach { recipe ->
            TmNavRow(title = recipe.name, subtitle = recipe.prompt, onClick = { viewingRecipe = recipe to true })
        }
        TmOutlinedButton(
            label = stringResource(R.string.settings_recipe_add),
            onClick = { editingRecipe = Recipe("", "") },
            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
        )
    }
}

// ── S3: Export & backup ─────────────────────────────────────────────────────

@Composable
internal fun ExportPage(viewModel: SettingsViewModel, snackbarHostState: SnackbarHostState) {
    val name by viewModel.exportLocationName.collectAsStateWithLifecycle()
    val uri by viewModel.exportLocationUri.collectAsStateWithLifecycle()
    val unexported by viewModel.unexportedCount.collectAsStateWithLifecycle()
    val repairing by viewModel.repairingExports.collectAsStateWithLifecycle()
    val restoring by viewModel.restoringNotes.collectAsStateWithLifecycle()
    val migrating by viewModel.migrating.collectAsStateWithLifecycle()
    val format by viewModel.exportFormat.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.settings_export_no_app)
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked != null) viewModel.onExportLocationPicked(picked)
    }

    if (confirmRestore) {
        TmConfirmDialog(
            title = stringResource(R.string.settings_restore_title),
            body = stringResource(R.string.settings_restore_body),
            confirmLabel = stringResource(R.string.settings_restore_confirm),
            onConfirm = {
                confirmRestore = false
                viewModel.restoreFromExportFolder()
            },
            onDismiss = { confirmRestore = false },
        )
    }
    if (confirmUnlink) {
        TmConfirmDialog(
            title = stringResource(R.string.settings_unlink_title),
            body = stringResource(R.string.settings_unlink_body),
            confirmLabel = stringResource(R.string.settings_unlink_confirm),
            destructive = true,
            onConfirm = {
                confirmUnlink = false
                viewModel.clearExportLocation()
            },
            onDismiss = { confirmUnlink = false },
        )
    }

    PageColumn {
        SectionHeader(stringResource(R.string.settings_export_folder))
        TmListRow(
            title = name ?: stringResource(R.string.settings_export_pick),
            subtitle = stringResource(
                when {
                    migrating -> R.string.settings_export_moving
                    name == null -> R.string.settings_export_pick_help
                    else -> R.string.settings_export_saved_here
                },
            ),
            leading = { TmIcon(TmIcons.Folder, null) },
            trailing = {
                if (migrating) {
                    CircularProgressIndicator(color = TrailMix.colors.text, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    TmOutlinedButton(
                        label = stringResource(if (name == null) R.string.settings_export_choose else R.string.settings_export_change),
                        onClick = { picker.launch(null) },
                    )
                }
            },
        )
        if (unexported > 0) {
            HelpText(pluralStringResource(R.plurals.settings_export_behind_long, unexported, unexported))
        }
        Row(
            modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(TmSpacing.s),
        ) {
            TmTonalButton(
                label = stringResource(if (repairing) R.string.home_exporting else R.string.settings_export_now),
                onClick = viewModel::exportMissingNotes,
                enabled = uri != null && unexported > 0 && !repairing,
            )
            TmOutlinedButton(
                label = stringResource(R.string.settings_export_open),
                enabled = uri != null,
                onClick = {
                    openFolder(context, uri) { scope.launch { snackbarHostState.showSnackbar(noApp) } }
                },
            )
        }

        SectionHeader(stringResource(R.string.settings_format))
        ExportFormat.entries.forEach { option ->
            ChoiceRow(
                title = option.label,
                subtitle = stringResource(formatHelp(option)),
                selected = option == format,
                onClick = { viewModel.setExportFormat(option) },
            )
        }

        SectionHeader(stringResource(R.string.settings_restore))
        TmNavRow(
            title = stringResource(if (restoring) R.string.settings_restoring else R.string.settings_restore_row),
            subtitle = stringResource(R.string.settings_restore_help),
            onClick = { confirmRestore = true },
            enabled = uri != null && !restoring,
        )
        TmNavRow(
            title = stringResource(R.string.settings_unlink_row),
            onClick = { confirmUnlink = true },
            enabled = uri != null && !migrating,
        )
    }
}

private fun formatHelp(format: ExportFormat): Int = when (format) {
    ExportFormat.LLM_OPTIMIZED -> R.string.settings_format_llm
    ExportFormat.HUMAN_READABLE -> R.string.settings_format_human
    ExportFormat.PLAIN_TEXT -> R.string.settings_format_plain
}

/** UX-08: open the export folder in whatever file app handles it. */
private fun openFolder(context: Context, treeUriString: String?, onFailure: () -> Unit) {
    val stored = treeUriString ?: return
    runCatching {
        val treeUri = stored.toUri()
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }.onFailure { onFailure() }
}

// ── Privacy and About ───────────────────────────────────────────────────────

@Composable
internal fun PrivacyPage() {
    PageColumn {
        PlainSection(R.string.settings_privacy_audio_title, R.string.settings_privacy_audio_body)
        PlainSection(R.string.settings_privacy_local_title, R.string.settings_privacy_local_body)
        PlainSection(R.string.settings_privacy_folder_title, R.string.settings_privacy_folder_body)
    }
}

@Composable
private fun PlainSection(title: Int, body: Int) {
    Column(modifier = Modifier.padding(horizontal = TmSpacing.l, vertical = TmSpacing.m), verticalArrangement = Arrangement.spacedBy(TmSpacing.xs)) {
        Text(stringResource(title), style = TrailMix.type.heading, color = TrailMix.colors.text)
        Text(stringResource(body), style = TrailMix.type.body, color = TrailMix.colors.dim)
    }
}

@Composable
internal fun AboutPage() {
    val context = LocalContext.current
    PageColumn {
        TmListRow(title = stringResource(R.string.settings_about_app), subtitle = stringResource(R.string.settings_about_version, versionName(context)))
        SectionHeader(stringResource(R.string.settings_about_licences))
        PlainSection(R.string.settings_licence_sherpa, R.string.settings_licence_sherpa_body)
        PlainSection(R.string.settings_licence_fonts, R.string.settings_licence_fonts_body)
        PlainSection(R.string.settings_licence_icons, R.string.settings_licence_icons_body)
        SectionHeader(stringResource(R.string.settings_privacy))
        PlainSection(R.string.settings_about_privacy_title, R.string.settings_about_privacy_body)
    }
}
