package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.db.toTranscriptMarkdown
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

    /**
     * INT-05: `DocumentFile.findFile` enumerates every child of the directory it's called on
     * — `tree.findFile(folderName)` was a full listing of the user's whole export-location
     * tree root (a real Obsidian vault folder, potentially many files), and it ran once per
     * file written (note, transcript, and again inside the photo writer), so one merge with
     * a transcript and photos issued three full listings for one note. Caching the resolved
     * folder here means a whole export — however many files it writes — issues at most one.
     * Keyed by `treeUri|folderName` so a changed Export location (or renamed notes folder)
     * misses naturally rather than needing an explicit invalidation call; re-validated with
     * `exists()` on every use so a folder deleted outside the app (OBS-05's scenario) is
     * re-resolved rather than written into a dangling reference.
     */
    @Volatile private var cachedFolder: Pair<String, DocumentFile>? = null

    private fun resolveNotesFolder(treeUri: Uri, folderName: String): DocumentFile? {
        val key = "$treeUri|$folderName"
        cachedFolder?.let { (cachedKey, folder) ->
            if (cachedKey == key && folder.exists()) return folder
        }
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val folder = tree.findFile(folderName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(folderName)
            ?: return null
        cachedFolder = key to folder
        return folder
    }

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

    override fun exists(uriStr: String): Boolean = runCatching {
        DocumentFile.fromSingleUri(context, Uri.parse(uriStr))?.exists() == true
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
        selectedPhotoUris: List<String>,
    ): ExportedFiles? = withContext(Dispatchers.IO) {
        val locationUri = settingsRepository.exportLocationUri.first() ?: return@withContext null
        val folderName = settingsRepository.notesFolder.first()
        val folder = resolveNotesFolder(Uri.parse(locationUri), folderName) ?: return@withContext null
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

        // Photo-export feature: an empty selection on a note that already has photos tracked
        // means "re-export the same ones" (the repair pass has no UI to reselect), not "drop
        // them" — only an explicit empty *tracked* list means genuinely none were ever chosen.
        val photoUris = selectedPhotoUris.ifEmpty { note.exportedPhotoUris }
        val photos = PhotoExportWriter.copyInto(context, folder, photoUris)

        val noteUri = MarkdownExportWriter.writeIntoFolder(
            context = context,
            folder = folder,
            fileName = noteFileName,
            markdown = note.toMarkdown(recipeOutputs, noteLinkBase, transcriptLinkBase, format, photos),
            existingFileUri = note.obsidianFileUri,
        ) ?: return@withContext null

        val transcriptUri = if (note.transcript.any { it.text.isNotBlank() }) {
            MarkdownExportWriter.writeIntoFolder(
                context = context,
                folder = folder,
                fileName = transcriptFileName,
                markdown = note.toTranscriptMarkdown(noteLinkBase, transcriptLinkBase, format),
                existingFileUri = note.transcriptFileUri,
            )
        } else {
            null
        }

        ExportedFiles(note = noteUri.toString(), transcript = transcriptUri?.toString())
    }

    override suspend fun listExportedNotes(): List<ExportedNoteFile> = withContext(Dispatchers.IO) {
        val locationUri = settingsRepository.exportLocationUri.first() ?: return@withContext emptyList()
        val folderName = settingsRepository.notesFolder.first()
        val folder = resolveNotesFolder(Uri.parse(locationUri), folderName) ?: return@withContext emptyList()

        val children = folder.listFiles()
        val transcriptsByName = children.associateBy { it.name }

        children
            .filter { it.isFile && it.name?.endsWith(".md") == true && it.name?.endsWith(".transcript.md") != true }
            .mapNotNull { noteFile ->
                val noteMarkdown = readText(noteFile.uri) ?: return@mapNotNull null
                val transcriptName = noteFile.name!!.removeSuffix(".md") + ".transcript.md"
                val transcriptFile = transcriptsByName[transcriptName]
                ExportedNoteFile(
                    noteUri = noteFile.uri.toString(),
                    transcriptUri = transcriptFile?.uri?.toString(),
                    noteMarkdown = noteMarkdown,
                    transcriptMarkdown = transcriptFile?.let { readText(it.uri) },
                )
            }
    }

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
