package com.trailmix.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.speech.AsrLocales
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val darkOverride by viewModel.darkModeOverride.collectAsStateWithLifecycle()
    val exportLocationName by viewModel.exportLocationName.collectAsStateWithLifecycle()
    val exportLocationUri by viewModel.exportLocationUri.collectAsStateWithLifecycle()
    val defaultTemplate by viewModel.defaultTemplate.collectAsStateWithLifecycle()
    val asrLocaleTag by viewModel.asrLocaleTag.collectAsStateWithLifecycle()
    val customRecipes by viewModel.customRecipes.collectAsStateWithLifecycle()
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

    // UX-06 recipe editor dialog state: null = closed; Recipe("", "") = creating new.
    var editingRecipe by remember { mutableStateOf<Recipe?>(null) }
    editingRecipe?.let { recipe ->
        RecipeEditorDialog(
            initial = recipe,
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

        // Custom recipes (UX-06) — user-authored saved prompts, shown as chips on
        // Chat & Recipes after the built-ins and run through the same on-device path.
        SectionLabel(
            text = "Custom recipes",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "Your own saved prompts for Chat & Recipes — e.g. \"Draft a status update " +
                "for my manager from this note.\" The note and transcript are provided " +
                "automatically; the prompt just says what to do with them.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        customRecipes.forEach { recipe ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingRecipe = recipe }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recipe.name,
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = recipe.prompt,
                        color = c.dim,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "Edit",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
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

        // Default summary template (UX-02) — steers the structured-summary prompt at
        // merge time unless overridden on the Capture screen itself.
        SectionLabel(
            text = "Default summary template",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SummaryTemplate.entries.size) { i ->
                val option = SummaryTemplate.entries[i]
                val selected = option == defaultTemplate
                Text(
                    text = option.label,
                    color = if (selected) Color.White else c.dim,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (selected) c.amber else c.card)
                        .clickable { viewModel.setDefaultTemplate(option) }
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
 * Create/edit dialog for a custom recipe (UX-06): name + prompt, a one-line inline example
 * as the prompt placeholder (the lightweight guidance the row asked for), Delete only when
 * editing an existing recipe.
 */
@Composable
private fun RecipeEditorDialog(
    initial: Recipe,
    onSave: (name: String, prompt: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val c = TrailMix.colors
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    val valid = name.isNotBlank() && prompt.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = {
            Text(
                text = if (initial.name.isBlank()) "New recipe" else "Edit recipe",
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
                        if (name.isEmpty()) Text("e.g. Status update", color = c.dim, fontSize = 14.sp)
                        inner()
                    },
                )
                Text(text = "PROMPT", color = c.dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
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
                        if (prompt.isEmpty()) {
                            Text(
                                text = "Tell the AI what to produce from the note, e.g. " +
                                    "\"Write a 3-sentence status update covering decisions " +
                                    "and open questions.\"",
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
                        text = "Delete recipe",
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
                    .clickable(enabled = valid) { onSave(name, prompt) }
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
