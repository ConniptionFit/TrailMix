package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Which SAF-backed destination a note's Markdown export is tracked against. Shared by the
 * Obsidian export (since v1.0.0) and Google Drive sync (INT-01, v1.5.0) — both go through
 * plain [android.provider.DocumentsContract] writes onto a user-picked tree, never a
 * network SDK, so the app never needs the INTERNET permission for either.
 */
enum class ExportTarget { OBSIDIAN, DRIVE }

/**
 * Shared SAF markdown-file writer used by both [com.trailmix.app.data.obsidian.ObsidianExporter]
 * and [com.trailmix.app.data.drive.DriveExporter] — both write the identical per-note Markdown
 * body into a folder under a user-picked SAF tree, differing only in which DataStore key holds
 * the tree URI. Factored out here so the two exporters, and the ad-hoc "Move" re-export (CAP-05
 * Part 1), don't duplicate the find-or-create-then-write dance.
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
     * Writes directly into the root of [treeUri] (no named subfolder) — used for "Move"
     * (CAP-05 Part 1), which re-exports a single note to a folder the user just picked via
     * `ACTION_OPEN_DOCUMENT_TREE` specifically as the new destination, not a standing vault.
     */
    fun writeIntoTreeRoot(
        context: Context,
        treeUri: Uri,
        fileName: String,
        markdown: String,
    ): Uri? = runCatching {
        val folder = DocumentFile.fromTreeUri(context, treeUri) ?: return@runCatching null
        write(context, folder, fileName, markdown, existingFileUri = null)
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

    fun buildFileName(title: String, createdAtEpochMs: Long): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(createdAtEpochMs))
        val slug = title.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "note" }
            .take(48)
        return "$date-$slug.md"
    }

    fun frontmatteredBody(markdown: String, createdAtEpochMs: Long, durationMs: Long, source: String): String =
        buildString {
            appendLine("---")
            appendLine("created: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(createdAtEpochMs))}")
            appendLine("source: $source")
            appendLine("duration_ms: $durationMs")
            appendLine("---")
            appendLine()
            append(markdown.trim())
            appendLine()
        }
}
