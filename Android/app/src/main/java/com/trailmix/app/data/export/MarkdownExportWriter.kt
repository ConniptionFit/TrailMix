package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * SAF markdown-file writer behind [NoteExporter] — writes the per-note Markdown body into
 * a folder under the user-picked Export location tree via plain
 * [android.provider.DocumentsContract] writes, never a network SDK, so the app never needs
 * the INTERNET permission. (Historically shared by the Obsidian and Google Drive exporters;
 * INT-02, v1.7.0 collapsed those into the single destination-agnostic [NoteExporter].)
 */
object MarkdownExportWriter {

    /**
     * Writes [markdown] into [folderName] under [treeUri]. Reuses the file at
     * [existingFileUri] in place (update, not a new file) if that URI still resolves and
     * exists; otherwise finds-or-creates by [fileName]. Returns the file's `content://` URI
     * on success, or null on any SAF failure (missing permission, revoked grant, provider
     * error, tree gone) — callers must treat every export as best-effort.
     */
    fun writeIntoFolder(
        context: Context,
        treeUri: Uri,
        folderName: String,
        fileName: String,
        markdown: String,
        existingFileUri: String?,
    ): Uri? = runCatching {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null
        val folder = tree.findFile(folderName)?.takeIf { it.isDirectory }
            ?: tree.createDirectory(folderName)
            ?: return@runCatching null
        write(context, folder, fileName, markdown, existingFileUri)
    }.getOrNull()

    /**
     * Display name of the file [existingFileUri] points at, or null if it isn't tracked, no
     * longer resolves, or has been deleted outside the app.
     *
     * OBS-03: [write] reuses a tracked file *in place*, so that file keeps whatever name it
     * was first created with even after the note is re-titled. Callers need to know that real
     * name before rendering, because the Markdown embeds wiki-links to these files — deriving
     * those from the current title instead produces links to a filename that was never
     * created. A null answer means "nothing tracked yet", where the caller's title-derived
     * name is correct, since that is what [write] will go on to create.
     */
    fun existingDisplayName(context: Context, existingFileUri: String?): String? =
        existingFileUri
            ?.let { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it)) }.getOrNull() }
            ?.takeIf { it.exists() }
            ?.name

    private fun write(
        context: Context,
        folder: DocumentFile,
        fileName: String,
        markdown: String,
        existingFileUri: String?,
    ): Uri? {
        val existing = existingFileUri
            ?.let { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it)) }.getOrNull() }
            ?.takeIf { it.exists() }
        val target = existing
            ?: folder.findFile(fileName)
            ?: folder.createFile("text/markdown", fileName)
            ?: return null

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(markdown.toByteArray(Charsets.UTF_8))
        } ?: return null

        return target.uri
    }
}
