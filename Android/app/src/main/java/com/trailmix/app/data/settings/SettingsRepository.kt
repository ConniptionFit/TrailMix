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
import com.trailmix.app.data.ai.VocabularyJson
import com.trailmix.app.data.ai.VocabularyTerm
import com.trailmix.app.data.export.ExportFormat
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
    private val exportFormatKey = stringPreferencesKey("export_format")
    private val vocabularyKey = stringPreferencesKey("vocabulary_terms")
    private val speakerDiarizationEnabledKey = booleanPreferencesKey("speaker_diarization_enabled")
    private val speakerRecognitionEnabledKey = booleanPreferencesKey("speaker_recognition_enabled")

    // Retired keys, deliberately no longer read or written:
    // - "name_variants" (CAL-04, v1.7.0)
    // - "drive_folder_uri" / "drive_folder_name" (INT-02, v1.7.0 — Google Drive sync removed)
    // - "picovoice_access_key" (AI-01/AI-12, v1.20.0-dev, 2026-09-13 — Falcon and then Eagle
    //   both removed in the sherpa-onnx migration; nothing left needs a Picovoice key at all)

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

    /**
     * User-taught vocabulary corrections (AI-08) — same DataStore-list pattern as custom
     * recipes/templates. Applied to each transcript line as it's finalized during capture
     * ([com.trailmix.app.data.speech.CaptureSessionManager]), never retroactively to notes
     * already saved, so editing the list can't silently rewrite an existing transcript.
     */
    val vocabularyTerms: Flow<List<VocabularyTerm>> =
        context.dataStore.data.map { VocabularyJson.decode(it[vocabularyKey]) }

    suspend fun setVocabularyTerms(terms: List<VocabularyTerm>) {
        context.dataStore.edit { prefs ->
            val cleaned = terms
                .map { VocabularyTerm(it.wrong.trim(), it.correct.trim()) }
                .filter { it.wrong.isNotBlank() && it.correct.isNotBlank() }
                .distinctBy { it.wrong.lowercase() }
            if (cleaned.isEmpty()) prefs.remove(vocabularyKey) else prefs[vocabularyKey] = VocabularyJson.encode(cleaned)
        }
    }

    /**
     * AI-01: off by default — turning this on is what makes
     * [com.trailmix.app.data.speech.CaptureSessionManager] retain a session's raw audio in
     * memory and, at merge time, run it through [com.trailmix.app.data.speech.SpeakerDiarizer]
     * for on-device diarization. Needs no account or key of any kind — sherpa-onnx's bundled
     * models since the 2026-09-13 migration, same as Falcon's own AAR-bundled model before it.
     * See [[Security and Privacy]] for the full writeup of what this does and does not send
     * anywhere.
     */
    val speakerDiarizationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[speakerDiarizationEnabledKey] ?: false }

    suspend fun setSpeakerDiarizationEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[speakerDiarizationEnabledKey] = enabled }
    }

    /**
     * Speaker recognition (Eagle path): off by default, independent of [speakerDiarizationEnabled]
     * — anonymous per-session diarization and persistent/live speaker recognition are separately
     * useful capabilities, not a shared switch. Its Settings UI section is hidden as of Phase 3
     * of the sherpa-onnx migration (2026-09-13, same session as the retired `picovoiceAccessKey`
     * this used to share with diarization above) — Eagle itself is gone and enrollment was never
     * built, so this currently has no consumer and no way for the user to even see the toggle.
     * Left wired rather than also retired: it is already a plain, backend-agnostic boolean, and
     * Phase 4 (AI-12, sherpa-onnx-based recognition) can bring the UI back onto this same key
     * with no migration of its own.
     */
    val speakerRecognitionEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[speakerRecognitionEnabledKey] ?: false }

    suspend fun setSpeakerRecognitionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[speakerRecognitionEnabledKey] = enabled }
    }

    /**
     * Default export format (the export-format dropdown feature) applied to every
     * fire-and-forget auto-export ([com.trailmix.app.data.export.NoteExporter]) and used as
     * the starting point for the one-off share-sheet picker. Defaults to
     * [ExportFormat.LLM_OPTIMIZED] so an existing install's auto-exports keep rendering
     * exactly as before unless the user opts into a different default.
     */
    val exportFormat: Flow<ExportFormat> = context.dataStore.data.map { prefs ->
        prefs[exportFormatKey]?.let { name ->
            runCatching { ExportFormat.valueOf(name) }.getOrNull()
        } ?: ExportFormat.LLM_OPTIMIZED
    }

    suspend fun setExportFormat(format: ExportFormat) {
        context.dataStore.edit { prefs -> prefs[exportFormatKey] = format.name }
    }
}
