package com.trailmix.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trailmix.app.data.model.StringListJson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "trailmix_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val darkModeOverrideKey = booleanPreferencesKey("dark_mode_override")
    private val vaultUriKey = stringPreferencesKey("obsidian_vault_uri")
    private val vaultNameKey = stringPreferencesKey("obsidian_vault_name")
    private val folderKey = stringPreferencesKey("obsidian_folder")
    private val projectionExplainerShownKey = booleanPreferencesKey("projection_explainer_shown")
    private val nameVariantsKey = stringPreferencesKey("name_variants")
    private val defaultSummaryTemplateKey = stringPreferencesKey("default_summary_template")
    private val driveUriKey = stringPreferencesKey("drive_folder_uri")
    private val driveFolderNameKey = stringPreferencesKey("drive_folder_name")
    private val asrLocaleTagKey = stringPreferencesKey("asr_locale_tag")

    /** null = follow the system setting (design default). */
    val darkModeOverride: Flow<Boolean?> = context.dataStore.data.map { it[darkModeOverrideKey] }

    /** Whether the one-time "audio only, not your screen" explainer has been shown. */
    val projectionExplainerShown: Flow<Boolean> =
        context.dataStore.data.map { it[projectionExplainerShownKey] ?: false }

    suspend fun markProjectionExplainerShown() {
        context.dataStore.edit { it[projectionExplainerShownKey] = true }
    }

    val vaultUri: Flow<String?> = context.dataStore.data.map { it[vaultUriKey] }
    val vaultName: Flow<String?> = context.dataStore.data.map { it[vaultNameKey] }
    val notesFolder: Flow<String> = context.dataStore.data.map { it[folderKey] ?: "TrailMix" }

    suspend fun setDarkModeOverride(value: Boolean?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(darkModeOverrideKey) else prefs[darkModeOverrideKey] = value
        }
    }

    suspend fun setVault(uri: String, name: String?) {
        context.dataStore.edit { prefs ->
            prefs[vaultUriKey] = uri
            if (name != null) prefs[vaultNameKey] = name else prefs.remove(vaultNameKey)
        }
    }

    suspend fun clearVault() {
        context.dataStore.edit {
            it.remove(vaultUriKey)
            it.remove(vaultNameKey)
        }
    }

    /**
     * Names/aliases the user goes by (CAL-03), e.g. "JP", "John Powers". Threaded into
     * the AI merge/chat prompts so the model can recognize the user under any of them.
     */
    val nameVariants: Flow<List<String>> =
        context.dataStore.data.map { StringListJson.decode(it[nameVariantsKey]) }

    suspend fun setNameVariants(names: List<String>) {
        context.dataStore.edit { prefs ->
            val cleaned = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (cleaned.isEmpty()) prefs.remove(nameVariantsKey) else prefs[nameVariantsKey] = StringListJson.encode(cleaned)
        }
    }

    /** Default [com.trailmix.app.data.model.SummaryTemplate] name applied to new captures unless overridden. */
    val defaultSummaryTemplate: Flow<String?> =
        context.dataStore.data.map { it[defaultSummaryTemplateKey] }

    suspend fun setDefaultSummaryTemplate(templateName: String?) {
        context.dataStore.edit { prefs ->
            if (templateName == null) prefs.remove(defaultSummaryTemplateKey) else prefs[defaultSummaryTemplateKey] = templateName
        }
    }

    /**
     * Google Drive sync folder (INT-01, v1.5.0) — the SAF tree URI a user picked via
     * `ACTION_OPEN_DOCUMENT_TREE` (which lists Drive as a document provider on a device
     * that has it configured). Absent = sync is a silent no-op, same fail-soft contract as
     * the Obsidian vault above. Stored the same way as the vault URI — DataStore, not a
     * Room row, since it's app-wide config rather than per-note.
     */
    val driveUri: Flow<String?> = context.dataStore.data.map { it[driveUriKey] }
    val driveFolderName: Flow<String?> = context.dataStore.data.map { it[driveFolderNameKey] }
    val driveFolder: Flow<String> = context.dataStore.data.map { it[driveFolderNameKey] ?: "TrailMix" }

    suspend fun setDrive(uri: String, name: String?) {
        context.dataStore.edit { prefs ->
            prefs[driveUriKey] = uri
            if (name != null) prefs[driveFolderNameKey] = name else prefs.remove(driveFolderNameKey)
        }
    }

    suspend fun clearDrive() {
        context.dataStore.edit {
            it.remove(driveUriKey)
            it.remove(driveFolderNameKey)
        }
    }

    /**
     * ASR locale setting (AI-02) — `MlKitTranscriber` used to hardcode `Locale.US`. Stored
     * as a BCP-47 tag (`"en-US"`); null = the default. See [com.trailmix.app.data.speech.AsrLocales]
     * for the curated supported list.
     */
    val asrLocaleTag: Flow<String?> = context.dataStore.data.map { it[asrLocaleTagKey] }

    suspend fun setAsrLocaleTag(tag: String?) {
        context.dataStore.edit { prefs ->
            if (tag == null) prefs.remove(asrLocaleTagKey) else prefs[asrLocaleTagKey] = tag
        }
    }
}
