package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.db.toTranscriptMarkdown
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
     * The two files one note produces (OBS-02, v1.11.0). [transcript] is null when the note
     * has no transcript lines — a typed-only note doesn't get an empty companion file.
     */
    data class ExportedFiles(val note: Uri, val transcript: Uri?)

    /**
     * Best-effort export/update-in-place into the configured export location. Writes the
     * summary note and, when there's a transcript, a companion `<name>.transcript.md`
     * beside it (OBS-02 — the transcript is split out so the note stays small enough to hand
     * to another model as context). Returns null if no location is set or the note write
     * failed; a failed *transcript* write is not fatal, it just leaves that URI null.
     */
    suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>> = emptyList(),
    ): ExportedFiles? = withContext(Dispatchers.IO) {
        val locationUri = settingsRepository.exportLocationUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        val tree = Uri.parse(locationUri)

        // OBS-03: resolve the names these two files will ACTUALLY have before rendering
        // either of them. A tracked file is rewritten in place and keeps its original name,
        // which stops matching the title as soon as the note is re-titled; rendering
        // title-derived wiki-links at that point yields links to files that don't exist.
        // Untracked (first export) falls back to the title-derived name, which is precisely
        // what the writer will create.
        val noteFileName = MarkdownExportWriter.existingDisplayName(context, note.obsidianFileUri)
            ?: NoteMarkdown.noteFileName(note.title, note.createdAtEpochMs)
        val transcriptFileName =
            MarkdownExportWriter.existingDisplayName(context, note.transcriptFileUri)
                ?: NoteMarkdown.transcriptFileName(note.title, note.createdAtEpochMs)
        val noteLinkBase = noteFileName.removeSuffix(".md")
        val transcriptLinkBase = transcriptFileName.removeSuffix(".md")

        val noteUri = MarkdownExportWriter.writeIntoFolder(
            context = context,
            treeUri = tree,
            folderName = folderName,
            fileName = noteFileName,
            markdown = note.toMarkdown(recipeOutputs, noteLinkBase, transcriptLinkBase),
            existingFileUri = note.obsidianFileUri,
        ) ?: return@withContext null

        val transcriptUri = if (note.transcript.any { it.text.isNotBlank() }) {
            MarkdownExportWriter.writeIntoFolder(
                context = context,
                treeUri = tree,
                folderName = folderName,
                fileName = transcriptFileName,
                markdown = note.toTranscriptMarkdown(noteLinkBase, transcriptLinkBase),
                existingFileUri = note.transcriptFileUri,
            )
        } else {
            null
        }

        ExportedFiles(note = noteUri, transcript = transcriptUri)
    }
}
