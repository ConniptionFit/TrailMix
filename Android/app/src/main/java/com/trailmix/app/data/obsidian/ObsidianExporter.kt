package com.trailmix.app.data.obsidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.export.MarkdownExportWriter
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class ObsidianExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Best-effort export/update-in-place into the configured vault folder. Returns the
     * file's `content://` URI on success (tracked on the note as `obsidianFileUri`), or
     * null if no vault is linked or the write failed for any reason.
     */
    suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
    ): Uri? = withContext(Dispatchers.IO) {
        val vaultUri = settingsRepository.vaultUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        MarkdownExportWriter.writeIntoFolder(
            context = context,
            treeUri = Uri.parse(vaultUri),
            folderName = folderName,
            fileName = MarkdownExportWriter.buildFileName(note.title, note.createdAtEpochMs),
            markdown = body(note, recipeOutputs),
            existingFileUri = note.obsidianFileUri,
        )
    }

    /**
     * "Move" (CAP-05 Part 1): re-export a single note straight into a folder the user just
     * picked via `ACTION_OPEN_DOCUMENT_TREE` as a one-off destination, replacing whatever
     * `obsidianFileUri` was previously tracked. Independent of the standing vault setting.
     */
    suspend fun exportNoteToPickedFolder(
        treeUri: Uri,
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
    ): Uri? = withContext(Dispatchers.IO) {
        MarkdownExportWriter.writeIntoTreeRoot(
            context = context,
            treeUri = treeUri,
            fileName = MarkdownExportWriter.buildFileName(note.title, note.createdAtEpochMs),
            markdown = body(note, recipeOutputs),
        )
    }

    fun openInObsidian(vaultName: String?, fileName: String): Intent? {
        if (vaultName.isNullOrBlank()) return null
        val fileWithoutExt = fileName.removeSuffix(".md")
        val uri = Uri.parse(
            "obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(fileWithoutExt)}",
        )
        return Intent(Intent.ACTION_VIEW, uri)
    }

    private fun body(note: NoteEntity, recipeOutputs: List<Pair<String, String>>): String =
        MarkdownExportWriter.frontmatteredBody(
            markdown = note.toMarkdown(recipeOutputs),
            createdAtEpochMs = note.createdAtEpochMs,
            durationMs = note.durationMs,
            source = "trailmix",
        )
}
