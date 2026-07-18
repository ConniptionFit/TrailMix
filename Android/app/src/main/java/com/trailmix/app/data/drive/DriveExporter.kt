package com.trailmix.app.data.drive

import android.content.Context
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

/**
 * Google Drive sync (INT-01, v1.5.0) — Storage Access Framework only, same architecture as
 * [com.trailmix.app.data.obsidian.ObsidianExporter]. "User defines the endpoint" means the
 * user picks a Drive-backed folder via the standard `ACTION_OPEN_DOCUMENT_TREE` picker (Drive
 * shows up as a document provider when the device has it installed/configured); this class
 * only ever calls [android.content.ContentResolver] against that SAF tree. It never talks to
 * a Drive REST endpoint and this app has no INTERNET permission — the Drive app/provider does
 * whatever network I/O actually moves the bytes off the device. See `Security and Privacy.md`
 * for the corresponding disclosure: this is the one feature where note content leaves the
 * device, and it is opt-in and user-configured.
 *
 * Sync trigger deliberately mirrors the Obsidian exporter: automatic on every merge/edit
 * (`NotesRepository.exportIfConfigured`), not a separate manual "sync now" action, for
 * consistency between the two export surfaces.
 */
@Singleton
class DriveExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /** Silent no-op if no Drive folder is configured — fail-soft by construction. */
    suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
    ): Uri? = withContext(Dispatchers.IO) {
        val driveUri = settingsRepository.driveUri.first() ?: return@withContext null
        val folderName = settingsRepository.driveFolder.first()
        MarkdownExportWriter.writeIntoFolder(
            context = context,
            treeUri = Uri.parse(driveUri),
            folderName = folderName,
            fileName = MarkdownExportWriter.buildFileName(note.title, note.createdAtEpochMs),
            markdown = body(note, recipeOutputs),
            existingFileUri = note.driveFileUri,
        )
    }

    /** "Move" (CAP-05 Part 1): re-export straight into a freshly picked folder, one-off. */
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

    private fun body(note: NoteEntity, recipeOutputs: List<Pair<String, String>>): String =
        MarkdownExportWriter.frontmatteredBody(
            markdown = note.toMarkdown(recipeOutputs),
            createdAtEpochMs = note.createdAtEpochMs,
            durationMs = note.durationMs,
            source = "trailmix",
        )
}
