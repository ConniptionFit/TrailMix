package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
) : ExportSink {

    override suspend fun isConfigured(): Boolean =
        settingsRepository.exportLocationUri.first() != null

    /**
     * REL-14: removing an exported file lives here, with the code that created it, rather
     * than in the repository — SAF is this package's business, and the repository's job is
     * only to decide *which* files a delete, restore or migration should take with it.
     */
    override fun deleteExported(uriStr: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uriStr))
    }.getOrDefault(false)

    /**
     * Best-effort export/update-in-place into the configured export location. Writes the
     * summary note and, when there's a transcript, a companion `<name>.transcript.md`
     * beside it (OBS-02 — the transcript is split out so the note stays small enough to hand
     * to another model as context). Returns null if no location is set or the note write
     * failed; a failed *transcript* write is not fatal, it just leaves that URI null.
     */
    override suspend fun exportNote(
        note: NoteEntity,
        recipeOutputs: List<Pair<String, String>>,
    ): ExportedFiles? = withContext(Dispatchers.IO) {
        val locationUri = settingsRepository.exportLocationUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        val tree = Uri.parse(locationUri)
        // Auto-export is fire-and-forget with no UI in the loop, so it always uses the
        // persisted default rather than asking — the share sheet is where a one-off
        // override belongs (export-format dropdown feature).
        val format = settingsRepository.exportFormat.first()

        // OBS-03: resolve the names these two files will ACTUALLY have before rendering
        // either of them. A tracked file is rewritten in place and keeps its original name,
        // which stops matching the title as soon as the note is re-titled; rendering
        // title-derived wiki-links at that point yields links to files that don't exist.
        // Untracked (first export) falls back to the title-derived name, which is precisely
        // what the writer will create.
        val noteFileName = MarkdownExportWriter.existingDisplayName(context, note.obsidianFileUri)
            ?: NoteMarkdown.noteFileName(note.title, note.createdAtEpochMs, format)
        val transcriptFileName =
            MarkdownExportWriter.existingDisplayName(context, note.transcriptFileUri)
                ?: NoteMarkdown.transcriptFileName(note.title, note.createdAtEpochMs, format)
        val noteLinkBase = noteFileName.substringBeforeLast(".")
        val transcriptLinkBase = transcriptFileName.substringBeforeLast(".")

        val noteUri = MarkdownExportWriter.writeIntoFolder(
            context = context,
            treeUri = tree,
            folderName = folderName,
            fileName = noteFileName,
            markdown = note.toMarkdown(recipeOutputs, noteLinkBase, transcriptLinkBase, format),
            existingFileUri = note.obsidianFileUri,
        ) ?: return@withContext null

        val transcriptUri = if (note.transcript.any { it.text.isNotBlank() }) {
            MarkdownExportWriter.writeIntoFolder(
                context = context,
                treeUri = tree,
                folderName = folderName,
                fileName = transcriptFileName,
                markdown = note.toTranscriptMarkdown(noteLinkBase, transcriptLinkBase, format),
                existingFileUri = note.transcriptFileUri,
            )
        } else {
            null
        }

        ExportedFiles(note = noteUri.toString(), transcript = transcriptUri?.toString())
    }
}
