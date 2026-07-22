package com.trailmix.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.ai.RecipesJson
import com.trailmix.app.data.model.CustomSummaryTemplate
import com.trailmix.app.data.model.CustomTemplatesJson
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

    // INT-02 (v1.7.0): the "Obsidian export" setting became the destination-agnostic
    // "Export location". The DataStore key names keep their historical "obsidian_*" spelling
    // on purpose so an existing user's configured folder survives the rename untouched.
    private val exportLocationUriKey = stringPreferencesKey("obsidian_vault_uri")
    private val exportLocationNameKey = stringPreferencesKey("obsidian_vault_name")
    private val folderKey = stringPreferencesKey("obsidian_folder")

    private val projectionExplainerShownKey = booleanPreferencesKey("projection_explainer_shown")
    private val defaultSummaryTemplateKey = stringPreferencesKey("default_summary_template")
    private val asrLocaleTagKey = stringPreferencesKey("asr_locale_tag")
    private val customRecipesKey = stringPreferencesKey("custom_recipes")
    private val customTemplatesKey = stringPreferencesKey("custom_summary_templates")

    // Retired keys, deliberately no longer read or written:
    // - "name_variants" (CAL-04, v1.7.0)
    // - "drive_folder_uri" / "drive_folder_name" (INT-02, v1.7.0 — Google Drive sync removed)

    /** null = follow the system setting (design default). */
    val darkModeOverride: Flow<Boolean?> = context.dataStore.data.map { it[darkModeOverrideKey] }

    /** Whether the one-time "audio only, not your screen" explainer has been shown. */
    val projectionExplainerShown: Flow<Boolean> =
        context.dataStore.data.map { it[projectionExplainerShownKey] ?: false }

    suspend fun markProjectionExplainerShown() {
        context.dataStore.edit { it[projectionExplainerShownKey] = true }
    }

    /**
     * Export location (INT-02, v1.7.0) — the single SAF tree URI note Markdown files are
     * written into. Destination-agnostic: an Obsidian vault, a cloud-synced folder, or any
     * plain folder all behave identically (SAF makes the provider's sync behavior the OS's
     * business, not TrailMix's). Absent = export is a silent no-op.
     */
    val exportLocationUri: Flow<String?> = context.dataStore.data.map { it[exportLocationUriKey] }
    val exportLocationName: Flow<String?> = context.dataStore.data.map { it[exportLocationNameKey] }
    val notesFolder: Flow<String> = context.dataStore.data.map { it[folderKey] ?: "TrailMix" }

    suspend fun setDarkModeOverride(value: Boolean?) {
        context.dataStore.edit { prefs ->
            if (value == null) prefs.remove(darkModeOverrideKey) else prefs[darkModeOverrideKey] = value
        }
    }

    suspend fun setExportLocation(uri: String, name: String?) {
        context.dataStore.edit { prefs ->
            prefs[exportLocationUriKey] = uri
            if (name != null) prefs[exportLocationNameKey] = name else prefs.remove(exportLocationNameKey)
        }
    }

    suspend fun clearExportLocation() {
        context.dataStore.edit {
            it.remove(exportLocationUriKey)
            it.remove(exportLocationNameKey)
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

    /**
     * User-defined recipes (UX-06, v1.7.0) — stored as a JSON list in DataStore (keeps the
     * DB at Room v7; recipes are app-wide config, not per-note data). Rendered as chips
     * after the built-ins on Chat & Recipes and executed through the exact same path.
     */
    val customRecipes: Flow<List<Recipe>> =
        context.dataStore.data.map { RecipesJson.decode(it[customRecipesKey]) }

    suspend fun setCustomRecipes(recipes: List<Recipe>) {
        context.dataStore.edit { prefs ->
            val cleaned = recipes
                .map { Recipe(it.name.trim(), it.prompt.trim()) }
                .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
                .distinctBy { it.name }
            if (cleaned.isEmpty()) prefs.remove(customRecipesKey) else prefs[customRecipesKey] = RecipesJson.encode(cleaned)
        }
    }

    /**
     * User-defined summary templates (AI-03, v1.8.0) — same DataStore-list pattern as
     * custom recipes. Selectable everywhere the built-in templates are (Capture screen,
     * Settings default) via [com.trailmix.app.data.model.TemplateOptions].
     */
    val customSummaryTemplates: Flow<List<CustomSummaryTemplate>> =
        context.dataStore.data.map { CustomTemplatesJson.decode(it[customTemplatesKey]) }

    suspend fun setCustomSummaryTemplates(templates: List<CustomSummaryTemplate>) {
        context.dataStore.edit { prefs ->
            val cleaned = templates
                .map { CustomSummaryTemplate(it.name.trim(), it.guidance.trim()) }
                .filter { it.name.isNotBlank() && it.guidance.isNotBlank() }
                .distinctBy { it.name }
            if (cleaned.isEmpty()) {
                prefs.remove(customTemplatesKey)
            } else {
                prefs[customTemplatesKey] = CustomTemplatesJson.encode(cleaned)
            }
        }
    }
}
