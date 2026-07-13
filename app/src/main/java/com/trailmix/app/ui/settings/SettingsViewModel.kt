package com.trailmix.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.AiAvailability
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val vaultUri: String? = null,
    val vaultName: String? = null,
    val notesFolder: String = "TrailMix",
    val aiAvailability: AiAvailability? = null,
    val isDownloading: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiProcessor: OnDeviceAiProcessor,
) : ViewModel() {
    private val aiState = MutableStateFlow<AiAvailability?>(null)
    private val downloading = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.vaultUri,
        settingsRepository.vaultName,
        settingsRepository.notesFolder,
        aiState,
        downloading,
    ) { uri, name, folder, ai, isDownloading ->
        SettingsUiState(
            vaultUri = uri,
            vaultName = name,
            notesFolder = folder,
            aiAvailability = ai,
            isDownloading = isDownloading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshAi()
    }

    fun refreshAi() {
        viewModelScope.launch {
            aiState.value = aiProcessor.checkAvailability()
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            downloading.value = true
            aiState.value = AiAvailability.Downloading
            aiState.value = aiProcessor.ensureModelReady()
            downloading.value = false
        }
    }

    fun linkVault(uri: String, name: String) {
        viewModelScope.launch {
            settingsRepository.setVault(uri, name)
        }
    }

    fun unlinkVault() {
        viewModelScope.launch {
            settingsRepository.clearVault()
        }
    }

    fun updateFolder(folder: String) {
        viewModelScope.launch {
            settingsRepository.setNotesFolder(folder)
        }
    }
}
