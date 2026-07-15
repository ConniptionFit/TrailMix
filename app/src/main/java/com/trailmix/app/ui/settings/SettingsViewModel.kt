package com.trailmix.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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

    /** Names/aliases the user goes by, for AI attribution across the transcript (CAL-03). */
    val nameVariants: StateFlow<List<String>> = settingsRepository.nameVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val defaultTemplate: StateFlow<SummaryTemplate> = settingsRepository.defaultSummaryTemplate
        .map { SummaryTemplate.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryTemplate.NONE)

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

    fun addNameVariant(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { settingsRepository.setNameVariants(nameVariants.value + name.trim()) }
    }

    fun removeNameVariant(name: String) {
        viewModelScope.launch { settingsRepository.setNameVariants(nameVariants.value - name) }
    }

    fun setDefaultTemplate(template: SummaryTemplate) {
        viewModelScope.launch {
            settingsRepository.setDefaultSummaryTemplate(if (template == SummaryTemplate.NONE) null else template.name)
        }
    }
}
