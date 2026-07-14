package com.trailmix.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val darkModeOverride: StateFlow<Boolean?> = settingsRepository.darkModeOverride
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val vaultName: StateFlow<String?> = settingsRepository.vaultName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkModeOverride(enabled) }
    }

    fun onVaultPicked(uri: Uri) {
        viewModelScope.launch {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            settingsRepository.setVault(uri.toString(), name)
        }
    }

    fun clearVault() {
        viewModelScope.launch { settingsRepository.clearVault() }
    }
}
