package com.trailmix.app.ui.settings

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.speech.AsrLocales
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val darkOverride by viewModel.darkModeOverride.collectAsStateWithLifecycle()
    val vaultName by viewModel.vaultName.collectAsStateWithLifecycle()
    val driveFolderName by viewModel.driveFolderName.collectAsStateWithLifecycle()
    val nameVariants by viewModel.nameVariants.collectAsStateWithLifecycle()
    val defaultTemplate by viewModel.defaultTemplate.collectAsStateWithLifecycle()
    val asrLocaleTag by viewModel.asrLocaleTag.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val systemDark = isSystemInDarkTheme()
    val darkOn = darkOverride ?: systemDark

    val vaultPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onVaultPicked(uri) }

    val drivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onDrivePicked(uri) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
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
            text = "One opt-in exception: if you link a Google Drive folder below, that note's " +
                "Markdown leaves the device — written through Android's standard folder-sharing " +
                "picker, not by this app talking to the internet directly.",
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

        SectionLabel(
            text = "Obsidian export",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vaultPicker.launch(null) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (vaultName == null) "Link a vault folder" else "Vault: $vaultName",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (vaultName == null) {
                        "Optionally mirror merged notes as local Markdown files"
                    } else {
                        "New notes export to the TrailMix folder · tap to change"
                    },
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (vaultName != null) {
                Text(
                    text = "Unlink",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .clickable { viewModel.clearVault() }
                        .padding(8.dp),
                )
            }
        }

        // Google Drive sync (INT-01) — same SAF-only architecture and folder-picker pattern
        // as the Obsidian export above; the Drive app/provider does whatever network I/O
        // actually moves the bytes, TrailMix itself still has no INTERNET permission.
        SectionLabel(
            text = "Google Drive sync",
            modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
        )
        Text(
            text = "Note content leaves this device once you link a folder here — see " +
                "Privacy & security above. Everything else in TrailMix stays local.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { drivePicker.launch(null) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = if (driveFolderName == null) "Link a Drive folder" else "Drive: $driveFolderName",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (driveFolderName == null) {
                        "Optionally mirror merged notes into a folder in Google Drive"
                    } else {
                        "New notes sync to the TrailMix folder · tap to change"
                    },
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (driveFolderName != null) {
                Text(
                    text = "Unlink",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .clickable { viewModel.clearDrive() }
                        .padding(8.dp),
                )
            }
        }

        // Name variants (CAL-03) — aliases the AI should recognize as the user across
        // transcript content, e.g. "JP", "John", "John Powers".
        SectionLabel(
            text = "Name variants",
            modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
        )
        Text(
            text = "Add every name you go by so chat and summaries can recognize you in the transcript.",
            color = c.dim,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        var newName by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.card)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                cursorBrush = SolidColor(c.amber),
                singleLine = true,
            )
            Text(
                text = "Add",
                color = c.amber,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        viewModel.addNameVariant(newName)
                        newName = ""
                    }
                    .padding(12.dp),
            )
        }
        if (nameVariants.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                nameVariants.forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = name, color = c.text, fontSize = 14.sp)
                        Text(
                            text = "Remove",
                            color = c.dim,
                            fontSize = 12.5.sp,
                            modifier = Modifier
                                .clickable { viewModel.removeNameVariant(name) }
                                .padding(4.dp),
                        )
                    }
                }
            }
        }

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
