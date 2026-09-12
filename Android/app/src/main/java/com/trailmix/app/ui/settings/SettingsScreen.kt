package com.trailmix.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.ai.DEFAULT_RECIPES
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.model.CustomSummaryTemplate
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TemplateOptions
import com.trailmix.app.data.speech.AsrLocales
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.export.label
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val darkOverride by viewModel.darkModeOverride.collectAsStateWithLifecycle()
    val exportLocationName by viewModel.exportLocationName.collectAsStateWithLifecycle()
    val exportLocationUri by viewModel.exportLocationUri.collectAsStateWithLifecycle()
    val unexportedCount by viewModel.unexportedCount.collectAsStateWithLifecycle()
    val repairingExports by viewModel.repairingExports.collectAsStateWithLifecycle()
    val defaultTemplate by viewModel.defaultTemplate.collectAsStateWithLifecycle()
    val asrLocaleTag by viewModel.asrLocaleTag.collectAsStateWithLifecycle()
    val customRecipes by viewModel.customRecipes.collectAsStateWithLifecycle()
    val customTemplates by viewModel.customTemplates.collectAsStateWithLifecycle()
    val exportFormat by viewModel.exportFormat.collectAsStateWithLifecycle()
    val migrating by viewModel.migrating.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkOn = darkOverride ?: systemDark
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    val exportLocationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onExportLocationPicked(uri) }

    // UX-14 prompt viewer state: the recipe whose "logic" is being viewed, and whether
    // it's a custom one (custom → the viewer offers Edit).
    var viewingRecipe by remember { mutableStateOf<Recipe?>(null) }
    var viewingIsCustom by remember { mutableStateOf(false) }

    // UX-06 recipe editor dialog state: null = closed; Recipe("", "") = creating new.
    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }

    // AI-03 template viewer/editor state — mirrors the recipe dialogs. Built-in templates
    // are viewed through the same (name, guidance) shape customs use.
    var viewingTemplate by remember { mutableStateOf<CustomSummaryTemplate?>(null) }
    var viewingTemplateIsCustom by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<CustomSummaryTemplate?>(null) }

    viewingRecipe?.let { recipe ->
        PromptViewerDialog(
            title = recipe.name,
            kindLabel = if (viewingIsCustom) "CUSTOM RECIPE — PROMPT" else "BUILT-IN RECIPE — PROMPT",
            body = recipe.prompt,
            footer = "The note and its transcript are supplied automatically when the " +
                "recipe runs — the prompt describes what to produce from them.",
            onEdit = if (viewingIsCustom) {
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
        NamedPromptEditorDialog(
            initialName = recipe.name,
            initialText = recipe.prompt,
            newTitle = "New recipe",
            editTitle = "Edit recipe",
            nameHint = "e.g. Status update",
            textLabel = "PROMPT",
            textHint = "Tell the AI what to produce from the note, e.g. " +
                "\"Write a 3-sentence status update covering decisions " +
                "and open questions.\"",
            deleteLabel = "Delete recipe",
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
    viewingTemplate?.let { template ->
        PromptViewerDialog(
            title = template.name,
            kindLabel = if (viewingTemplateIsCustom) {
                "CUSTOM TEMPLATE — PROMPT GUIDANCE"
            } else {
                "BUILT-IN TEMPLATE — PROMPT GUIDANCE"
            },
            body = template.guidance,
            footer = "This guidance is spliced into the structuring prompt when a capture " +
                "ends, steering which sections the summary is grouped into. The rest of " +
                "the prompt (JSON shape, factual-only rules) is fixed.",
            onEdit = if (viewingTemplateIsCustom) {
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
        NamedPromptEditorDialog(
            initialName = template.name,
            initialText = template.guidance,
            newTitle = "New template",
            editTitle = "Edit template",
            nameHint = "e.g. Sales call",
            textLabel = "GUIDANCE",
            textHint = "A sentence steering the summary's sections, e.g. \"This is a " +
                "sales call — prefer sections like Customer Needs, Objections, " +
                "Pricing, Next Steps.\"",
            deleteLabel = "Delete template",
            onSave = { name, guidance ->
                viewModel.saveTemplate(name, guidance, originalName = template.name.ifBlank { null })
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

    Box(modifier = Modifier.fillMaxSize().background(c.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
        Text(
            text = "Settings",
            color = c.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp, bottom = 18.dp),
        )

        // Dark mode row, hairline-bounded
        Hairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Dark mode",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (darkOverride == null) "Matches system setting" else "Manual override",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            TrackSwitch(on = darkOn, onToggle = { viewModel.setDarkMode(!darkOn) })
        }
        Hairline()

        // SEC-02 (v1.7.0): honest local-only disclosure. The Google Drive sync exception is
        // gone with the feature (INT-02); the one remaining nuance is that the user-chosen
        // export folder may itself be cloud-synced — that's the folder provider's behavior,
        // stated plainly instead of overclaiming "nothing ever leaves the device".
        SectionLabel(
            text = "Privacy & security",
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
        )
        Text(
            text = "Zero-retention audio — consumed in memory by the on-device recognizer, never written to disk or sent anywhere.",
            color = c.dim,
            fontSize = 13.5.sp,
            lineHeight = 21.6.sp, // 1.6
        )
        Text(
            text = "100% on-device — transcription and AI run locally. This app requests no network permission at all.",
            color = c.dim,
            fontSize = 13.5.sp,
            lineHeight = 21.6.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "One nuance: if the export location you pick below is a folder another app " +
                "syncs to the cloud (like a Drive folder), that app may upload your note files. " +
                "TrailMix itself only ever writes them locally.",
            color = c.dim,
            fontSize = 13.5.sp,
            lineHeight = 21.6.sp,
            modifier = Modifier.padding(top = 10.dp),
        )

        SectionLabel(
            text = "Speech recognition language",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        Text(
            text = "Language the on-device recognizer listens for (AI-02).",
            color = c.dim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AsrLocales.options.size) { i ->
                val option = AsrLocales.options[i]
                val selected = option.tag == (asrLocaleTag ?: AsrLocales.default.tag)
                Text(
                    text = option.label,
                    color = if (selected) Color.White else c.dim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.amber else c.card)
                        .clickable { viewModel.setAsrLocale(option.tag) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Export location (INT-02, v1.7.0 — formerly "Obsidian export"): one
        // destination-agnostic SAF folder; picking a new one auto-migrates existing files.
        SectionLabel(
            text = "Export location",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !migrating) { exportLocationPicker.launch(null) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (exportLocationName == null) "Pick a folder" else "Folder: $exportLocationName",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = when {
                        migrating -> "Moving your notes to the new folder…"
                        exportLocationName == null ->
                            "Optionally save merged notes as Markdown files — an Obsidian " +
                                "vault, a synced folder, anywhere"
                        else -> "Notes save to the TrailMix folder here · tap to change " +
                            "(existing files move automatically)"
                    },
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (migrating) {
                CircularProgressIndicator(
                    color = c.amber,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else if (exportLocationName != null) {
                Text(
                    text = "Unlink",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .clickable { viewModel.clearExportLocation() }
                        .padding(8.dp),
                )
            }
        }
        // OBS-04: notes with no exported file behind them. Only rendered when there is
        // something wrong — a silent "you're backed up" is the failure mode this exists to
        // prevent, but a permanent zero-state row would just be furniture.
        if (unexportedCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = "$unexportedCount note${if (unexportedCount == 1) "" else "s"} " +
                        "${if (unexportedCount == 1) "isn't" else "aren't"} exported",
                    color = c.amber,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (repairingExports) "Exporting…" else "Export now",
                    color = if (repairingExports) c.dim.copy(alpha = 0.5f) else c.amber,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(c.card)
                        .clickable(enabled = !repairingExports) { viewModel.exportMissingNotes() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Text(
                text = "These notes live only inside TrailMix until they're exported — " +
                    "uninstalling or clearing app data would lose them.",
                color = c.dim,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // "Open folder" (UX-08) — replaces the long-press "Open file location" action.
        val hasLocation = exportLocationUri != null
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = "Open folder",
                color = if (hasLocation) c.amber else c.dim.copy(alpha = 0.5f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(100.dp))
                    .background(c.card)
                    .clickable(enabled = hasLocation) {
                        exportLocationUri?.let { uriStr ->
                            runCatching {
                                val treeUri = Uri.parse(uriStr)
                                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri,
                                    DocumentsContract.getTreeDocumentId(treeUri),
                                )
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                )
                            }.onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar("No app available to open this folder")
                                }
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            if (!hasLocation) {
                Text(
                    text = "Pick an export location first",
                    color = c.dim,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }

        // Built-in recipes (UX-14) — read-only list of the standard Chat & Recipes prompts;
        // press-and-hold (or tap) any row to see the full prompt it runs.
        SectionLabel(
            text = "Built-in recipes",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "The standard Chat & Recipes prompts. Tap a recipe to see the exact " +
                "prompt it runs.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        DEFAULT_RECIPES.forEach { recipe ->
            PromptRow(
                name = recipe.name,
                preview = recipe.prompt,
                trailing = null,
                onTrailingClick = null,
                onClick = {
                    viewingIsCustom = false
                    viewingRecipe = recipe
                },
                onLongClick = {
                    viewingIsCustom = false
                    viewingRecipe = recipe
                },
            )
        }

        // Custom recipes (UX-06) — user-authored saved prompts, shown as chips on
        // Chat & Recipes after the built-ins and run through the same on-device path.
        // UX-14: press-and-hold views the full prompt (with Edit); tap still edits directly.
        SectionLabel(
            text = "Custom recipes",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "Your own saved prompts for Chat & Recipes — e.g. \"Draft a status update " +
                "for my manager from this note.\" The note and transcript are provided " +
                "automatically; the prompt just says what to do with them. Tap a recipe " +
                "to view its prompt; tap Edit to change it.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        customRecipes.forEach { recipe ->
            PromptRow(
                name = recipe.name,
                preview = recipe.prompt,
                trailing = "Edit",
                onTrailingClick = { editingRecipe = recipe },
                // UX-14 fix (user-reported): press-and-hold proved unreliable with a real
                // finger inside the scrolling settings column — tap now opens the prompt
                // viewer (long-press kept as a shortcut); direct edit moved to the
                // trailing Edit button and the viewer's own Edit action.
                onClick = {
                    viewingIsCustom = true
                    viewingRecipe = recipe
                },
                onLongClick = {
                    viewingIsCustom = true
                    viewingRecipe = recipe
                },
            )
        }
        Text(
            text = "+ Add recipe",
            color = c.amber,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(c.card)
                .clickable { editingRecipe = Recipe("", "") }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        // Built-in summary templates (AI-03) — read-only list of the structuring guidance
        // each template splices into the End & Merge prompt; tap to view the exact text.
        SectionLabel(
            text = "Built-in summary templates",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "Templates steer how End & Merge groups a note's summary into sections. " +
                "Tap a template to see the exact guidance it adds to the prompt.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SummaryTemplate.entries.forEach { template ->
            val asViewable = CustomSummaryTemplate(template.label, template.guidance)
            PromptRow(
                name = template.label,
                preview = template.guidance,
                trailing = null,
                onTrailingClick = null,
                onClick = {
                    viewingTemplateIsCustom = false
                    viewingTemplate = asViewable
                },
                onLongClick = {
                    viewingTemplateIsCustom = false
                    viewingTemplate = asViewable
                },
            )
        }

        // Custom summary templates (AI-03) — user-authored guidance, selectable everywhere
        // the built-ins are (Capture screen chips and the default below).
        SectionLabel(
            text = "Custom summary templates",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "Your own templates for the kinds of meetings you actually have. The " +
                "guidance is one or two sentences telling the AI which sections to prefer. " +
                "Tap a template to view its guidance; tap Edit to change it.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        customTemplates.forEach { template ->
            PromptRow(
                name = template.name,
                preview = template.guidance,
                trailing = "Edit",
                onTrailingClick = { editingTemplate = template },
                onClick = {
                    viewingTemplateIsCustom = true
                    viewingTemplate = template
                },
                onLongClick = {
                    viewingTemplateIsCustom = true
                    viewingTemplate = template
                },
            )
        }
        Text(
            text = "+ Add template",
            color = c.amber,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(100.dp))
                .background(c.card)
                .clickable { editingTemplate = CustomSummaryTemplate("", "") }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        // Default summary template (UX-02) — steers the structured-summary prompt at
        // merge time unless overridden on the Capture screen itself. AI-03: custom
        // templates are selectable here too.
        SectionLabel(
            text = "Default summary template",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        val templateOptions = TemplateOptions.all(customTemplates)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templateOptions.size) { i ->
                val option = templateOptions[i]
                val selected = option.stored == defaultTemplate
                Text(
                    text = option.label,
                    color = if (selected) Color.White else c.dim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.amber else c.card)
                        .clickable { viewModel.setDefaultTemplate(option.stored) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Export format (the export-format dropdown feature): default rendering for
        // auto-export; the share sheet starts from this but can override it per-share.
        SectionLabel(
            text = "Export format",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        Text(
            text = "LLM-optimized carries frontmatter and source tags for pasting into another " +
                "model. Human-readable drops both for easier reading. Plain text has no " +
                "Markdown at all.",
            color = c.dim,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ExportFormat.entries.size) { i ->
                val option = ExportFormat.entries[i]
                val selected = option == exportFormat
                Text(
                    text = option.label,
                    color = if (selected) Color.White else c.dim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.amber else c.card)
                        .clickable { viewModel.setExportFormat(option) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(modifier = Modifier.size(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

/**
 * Create/edit dialog for a named prompt-ish thing — custom recipes (UX-06) and custom
 * summary templates (AI-03) share this: name + free text, inline example placeholders,
 * Delete only when editing an existing entry ([onDelete] non-null).
 */
@Composable
private fun NamedPromptEditorDialog(
    initialName: String,
    initialText: String,
    newTitle: String,
    editTitle: String,
    nameHint: String,
    textLabel: String,
    textHint: String,
    deleteLabel: String,
    onSave: (name: String, text: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    val valid = name.isNotBlank() && text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = {
            Text(
                text = if (initialName.isBlank()) newTitle else editTitle,
                color = c.text,
                fontSize = 17.sp,
            )
        },
        text = {
            Column {
                Text(text = "NAME", color = c.dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.background)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(c.amber),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (name.isEmpty()) Text(nameHint, color = c.dim, fontSize = 14.sp)
                        inner()
                    },
                )
                Text(text = textLabel, color = c.dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.background)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(c.amber),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = textHint,
                                color = c.dim,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                        inner()
                    },
                )
                onDelete?.let { delete ->
                    Text(
                        text = deleteLabel,
                        color = c.recordingRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clickable { delete() }
                            .padding(4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Text(
                text = "Save",
                color = if (valid) c.amber else c.dim.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = valid) { onSave(name, text) }
                    .padding(8.dp),
            )
        },
        dismissButton = {
            Text(
                text = "Cancel",
                color = c.dim,
                fontSize = 14.sp,
                modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
            )
        },
    )
}

/** One name + one-line-preview row in Settings — recipe or summary template, built-in or custom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PromptRow(
    name: String,
    preview: String,
    trailing: String?,
    onTrailingClick: (() -> Unit)?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = TrailMix.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = c.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = preview,
                color = c.dim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = if (onTrailingClick != null) c.amber else c.dim,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(100.dp))
                    .let { m -> if (onTrailingClick != null) m.clickable { onTrailingClick() } else m }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Read-only viewer for "the logic" behind a row — a recipe's full prompt (UX-14) or a
 * summary template's guidance sentence (AI-03). Custom entries get an Edit action that
 * hands off to [NamedPromptEditorDialog].
 */
@Composable
private fun PromptViewerDialog(
    title: String,
    kindLabel: String,
    body: String,
    footer: String,
    onEdit: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(title, color = c.text, fontSize = 17.sp) },
        text = {
            Column {
                Text(
                    text = kindLabel,
                    color = c.dim,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    color = c.text,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.background)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Text(
                    text = footer,
                    color = c.dim,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            if (onEdit != null) {
                Text(
                    text = "Edit",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onEdit() }.padding(8.dp),
                )
            } else {
                Text(
                    text = "Close",
                    color = c.amber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
                )
            }
        },
        dismissButton = {
            if (onEdit != null) {
                Text(
                    text = "Close",
                    color = c.dim,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(8.dp),
                )
            }
        },
    )
}

@Composable
private fun Hairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TrailMix.colors.border),
    )
}

/** 46×26 track, 20dp thumb animating 3dp↔23dp (design spec). */
@Composable
private fun TrackSwitch(on: Boolean, onToggle: () -> Unit) {
    val c = TrailMix.colors
    val thumbOffset by animateDpAsState(targetValue = if (on) 23.dp else 3.dp, label = "thumb")
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(if (on) c.amber else c.border)
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .align(Alignment.CenterStart)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
