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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.ui.components.SectionLabel
import com.trailmix.app.ui.theme.TrailMix

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val darkOverride by viewModel.darkModeOverride.collectAsStateWithLifecycle()
    val vaultName by viewModel.vaultName.collectAsStateWithLifecycle()
    val c = TrailMix.colors
    val systemDark = isSystemInDarkTheme()
    val darkOn = darkOverride ?: systemDark

    val vaultPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onVaultPicked(uri) }

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
            text = "Capture",
            modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
        )
        val deviceAudioDefault by viewModel.deviceAudioByDefault.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Capture device audio by default",
                    color = c.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Asks for Android's screen-share permission when a capture " +
                        "starts. TrailMix takes audio only — your screen is never recorded.",
                    color = c.dim,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 2.dp, end = 12.dp),
                )
            }
            TrackSwitch(
                on = deviceAudioDefault,
                onToggle = { viewModel.setDeviceAudioByDefault(!deviceAudioDefault) },
            )
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
