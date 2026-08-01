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
