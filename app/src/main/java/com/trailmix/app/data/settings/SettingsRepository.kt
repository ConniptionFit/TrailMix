package com.trailmix.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
    private val vaultUriKey = stringPreferencesKey("obsidian_vault_uri")
    private val vaultNameKey = stringPreferencesKey("obsidian_vault_name")
    private val folderKey = stringPreferencesKey("obsidian_folder")

    val vaultUri: Flow<String?> = context.dataStore.data.map { it[vaultUriKey] }
    val vaultName: Flow<String?> = context.dataStore.data.map { it[vaultNameKey] }
    val notesFolder: Flow<String> = context.dataStore.data.map { it[folderKey] ?: "TrailMix" }

    suspend fun setVault(uri: String, name: String?) {
        context.dataStore.edit { prefs ->
            prefs[vaultUriKey] = uri
            if (name != null) prefs[vaultNameKey] = name else prefs.remove(vaultNameKey)
        }
    }

    suspend fun setNotesFolder(folder: String) {
        context.dataStore.edit { it[folderKey] = folder.ifBlank { "TrailMix" } }
    }

    suspend fun clearVault() {
        context.dataStore.edit {
            it.remove(vaultUriKey)
            it.remove(vaultNameKey)
        }
    }
}
