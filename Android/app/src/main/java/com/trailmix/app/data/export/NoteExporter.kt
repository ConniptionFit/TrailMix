package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Writes a note's Markdown into the configured Export location (INT-02, v1.7.0 — formerly
 * `ObsidianExporter`; renamed because the destination is user-chosen and agnostic: an
 * Obsidian vault, a cloud-synced folder, anything SAF can reach). Strictly SAF — never a
 * network SDK; the app has no INTERNET permission, and whether the picked folder is synced
 * anywhere is the folder provider's business.
 */
@Singleton
class NoteExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Best-effort export/update-in-place into the configured export location. Returns the
     * file's `content://` URI on success (tracked on the note as `obsidianFileUri` — the
     * column keeps its historical name), or null if no location is set or the write failed.
     */
    suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
    ): Uri? = withContext(Dispatchers.IO) {
        val locationUri = settingsRepository.exportLocationUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        MarkdownExportWriter.writeIntoFolder(
            context = context,
            treeUri = Uri.parse(locationUri),
            folderName = folderName,
            fileName = MarkdownExportWriter.buildFileName(note.title, note.createdAtEpochMs),
            markdown = MarkdownExportWriter.frontmatteredBody(
                markdown = note.toMarkdown(recipeOutputs),
                createdAtEpochMs = note.createdAtEpochMs,
                durationMs = note.durationMs,
                source = "trailmix",
            ),
            existingFileUri = note.obsidianFileUri,
        )
    }
}
