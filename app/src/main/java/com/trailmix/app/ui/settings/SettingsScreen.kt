package com.trailmix.app.ui.settings

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.trailmix.app.data.ai.AiAvailability
import com.trailmix.app.ui.components.PrimaryPillButton
import com.trailmix.app.ui.theme.TrailMixAccent
import com.trailmix.app.ui.theme.TrailMixCard
import com.trailmix.app.ui.theme.TrailMixSecondaryText

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vaultPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            val name = DocumentsContract.getTreeDocumentId(uri)
                .substringAfterLast(':')
                .substringAfterLast('/')
                .ifBlank { "Obsidian Vault" }
            viewModel.linkVault(uri.toString(), name)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SettingsCard(title = "On-device AI") {
            Text(
                text = when (val ai = state.aiAvailability) {
                    is AiAvailability.Available -> "Gemini Nano ready on this device."
                    is AiAvailability.Downloadable -> "Model downloadable. TrailMix will download it when needed."
                    is AiAvailability.Downloading -> "Downloading Gemini Nano…"
                    is AiAvailability.Unavailable -> ai.reason
                    null -> "Checking…"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (state.aiAvailability is AiAvailability.Downloadable) {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryPillButton(
                    text = "Download model",
                    onClick = viewModel::downloadModel,
                    enabled = !state.isDownloading,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsCard(title = "Obsidian") {
            Text(
                text = if (state.vaultUri != null) {
                    "Linked vault: ${state.vaultName ?: "Selected folder"}"
                } else {
                    "Link your Obsidian vault folder so TrailMix can write Markdown notes."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(12.dp))
            PrimaryPillButton(
                text = if (state.vaultUri == null) "Link Obsidian vault" else "Change vault",
                onClick = { vaultPicker.launch(null) },
            )
            if (state.vaultUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryPillButton(text = "Unlink vault", onClick = viewModel::unlinkVault)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Notes folder",
                style = MaterialTheme.typography.labelLarge,
                color = TrailMixSecondaryText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.notesFolder,
                onValueChange = viewModel::updateFolder,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TrailMixAccent,
                    unfocusedBorderColor = TrailMixSecondaryText.copy(alpha = 0.4f),
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrailMixCard, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}
