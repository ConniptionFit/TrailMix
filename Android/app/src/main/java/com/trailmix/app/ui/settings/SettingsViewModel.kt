package com.trailmix.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val notesRepository: NotesRepository,
) : ViewModel() {

    val darkModeOverride: StateFlow<Boolean?> = settingsRepository.darkModeOverride
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Export location (INT-02, v1.7.0 — formerly the Obsidian vault link). */
    val exportLocationName: StateFlow<String?> = settingsRepository.exportLocationName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val exportLocationUri: StateFlow<String?> = settingsRepository.exportLocationUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val defaultTemplate: StateFlow<SummaryTemplate> = settingsRepository.defaultSummaryTemplate
        .map { SummaryTemplate.fromStored(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryTemplate.NONE)

    /** ASR locale (AI-02) — null means "use the default" (`AsrLocales.default`). */
    val asrLocaleTag: StateFlow<String?> = settingsRepository.asrLocaleTag
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** User-defined recipes (UX-06). */
    val customRecipes: StateFlow<List<Recipe>> = settingsRepository.customRecipes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True while the INT-02 export-location migration is moving files between folders. */
    private val _migrating = MutableStateFlow(false)
    val migrating: StateFlow<Boolean> = _migrating.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkModeOverride(enabled) }
    }

    /**
     * Export-location picker result (INT-02). Persists the SAF tree URI with a durable
     * read+write grant, then — if a different location was previously configured — runs the
     * auto-migration: every note with a tracked export file is written into the new folder,
     * its tracked URI updated, and the old file removed (per-note fail-soft; summarized in
     * one Snackbar).
     */
    fun onExportLocationPicked(uri: Uri) {
        viewModelScope.launch {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val previousUri = settingsRepository.exportLocationUri.first()
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            settingsRepository.setExportLocation(uri.toString(), name)

            if (previousUri != null && previousUri != uri.toString()) {
                _migrating.value = true
                try {
                    val result = notesRepository.migrateExports()
                    val message = buildString {
                        append("Moved ${result.moved} note${if (result.moved == 1) "" else "s"} to the new folder")
                        if (result.writeFailures > 0) append("; ${result.writeFailures} couldn't be written")
                        if (result.removeFailures > 0) {
                            append("; ${result.removeFailures} couldn't be removed from the old folder")
                        }
                    }
                    _snackbarMessage.tryEmit(message)
                } finally {
                    _migrating.value = false
                }
            }
        }
    }

    fun clearExportLocation() {
        viewModelScope.launch { settingsRepository.clearExportLocation() }
    }

    fun setDefaultTemplate(template: SummaryTemplate) {
        viewModelScope.launch {
            settingsRepository.setDefaultSummaryTemplate(if (template == SummaryTemplate.NONE) null else template.name)
        }
    }

    fun setAsrLocale(tag: String) {
        viewModelScope.launch { settingsRepository.setAsrLocaleTag(tag) }
    }

    /**
     * Create or update a custom recipe (UX-06). [originalName] is the name the recipe had
     * when the edit dialog opened (null when creating) so a rename replaces the old entry
     * instead of duplicating it. Names colliding with a built-in are rejected with a hint.
     */
    fun saveRecipe(name: String, prompt: String, originalName: String? = null) {
        val trimmedName = name.trim()
        val trimmedPrompt = prompt.trim()
        if (trimmedName.isBlank() || trimmedPrompt.isBlank()) return
        if (com.trailmix.app.data.ai.DEFAULT_RECIPES.any { it.name.equals(trimmedName, ignoreCase = true) }) {
            _snackbarMessage.tryEmit("\"$trimmedName\" is a built-in recipe name — pick another")
            return
        }
        viewModelScope.launch {
            val current = settingsRepository.customRecipes.first()
                .filterNot { it.name == originalName || it.name == trimmedName }
            settingsRepository.setCustomRecipes(current + Recipe(trimmedName, trimmedPrompt))
        }
    }

    fun deleteRecipe(name: String) {
        viewModelScope.launch {
            settingsRepository.setCustomRecipes(
                settingsRepository.customRecipes.first().filterNot { it.name == name },
            )
        }
    }
}
