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
     * Writes [markdown] into the already-resolved [folder]. Reuses the file at
     * [existingFileUri] in place (update, not a new file) if that URI still resolves and
     * exists; otherwise finds-or-creates by [fileName]. Returns the file's `content://` URI
     * on success, or null on any SAF failure (missing permission, revoked grant, provider
     * error, tree gone) — callers must treat every export as best-effort.
     *
     * INT-05: this used to take `treeUri`/`folderName` and resolve the folder itself via
     * `tree.findFile(folderName)`, which enumerates every child of the tree root — a full SAF
     * directory listing on every single call. [NoteExporter] now resolves and caches the
     * folder once per export (or reuses last export's), so a note+transcript export issues
     * one listing total instead of one per file.
     */
    fun writeIntoFolder(
        context: Context,
        folder: DocumentFile,
        fileName: String,
        markdown: String,
        existingFileUri: String?,
    ): Uri? = runCatching {
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
        // REL-15: recover before anything else touches this name — see [recoverStaleTempFile].
        recoverStaleTempFile(folder, fileName)

        val existing = existingFileUri
            ?.let { runCatching { DocumentFile.fromSingleUri(context, Uri.parse(it)) }.getOrNull() }
            ?.takeIf { it.exists() }
        // Export-format dropdown: a plain-text export uses the .txt extension, and some SAF
        // providers use the create-time MIME type to influence the actual extension they
        // append — a "text/markdown" .txt file has been observed growing a second .md suffix.
        val mimeType = if (fileName.endsWith(".txt")) "text/plain" else "text/markdown"
        val target = existing ?: folder.findFile(fileName)

        val bytes = markdown.toByteArray(Charsets.UTF_8)
        val tempName = "$fileName.tmp"
        // A leftover from an earlier failed attempt at *this* write (not the crash-recovery
        // case above, which already handled a temp with no target) — createFile would
        // otherwise uniquify onto "$fileName.tmp (1)" and this call's own temp would never be
        // found again next time.
        folder.findFile(tempName)?.delete()
        val temp = folder.createFile(mimeType, tempName) ?: return null

        val wrote = runCatching {
            context.contentResolver.openOutputStream(temp.uri, "wt")?.use { it.write(bytes) }
        }.getOrNull() != null
        // REL-15: "wt" truncates and then writes, so a write that ends early leaves a file
        // shorter than the note it is supposed to hold. An `IOException` on the way (a
        // revoked grant, a dead provider) already surfaces as a failure here, but a full disk
        // can end a write short *without* throwing — checked the same one-sided way either
        // way, on the *temp* file, so a bad write never touches the real target at all.
        if (!wrote || isShortWrite(runCatching { temp.length() }.getOrDefault(UNKNOWN_LENGTH), bytes.size)) {
            runCatching { temp.delete() }
            return null
        }

        // The complete new content is safely on disk under the temp name — only now do we
        // touch the real target. `DocumentsContract.renameDocument` can't overwrite an
        // existing name (providers uniquify to "name (1).md" instead), so the target has to
        // go first; a process death between these two lines leaves no file at `fileName`, but
        // the temp file (recovered by the next export's [recoverStaleTempFile] call, or found
        // by this same file's own `existingFileUri` fallback if the DB row already forgot
        // about it) still holds the complete content, which is the point of doing it this way
        // instead of the ordinary "wt" truncate-in-place this replaced.
        if (target != null && target.uri != temp.uri) runCatching { target.delete() }
        return if (runCatching { temp.renameTo(fileName) }.getOrDefault(false)) temp.uri else null
    }

    /**
     * REL-15: a crash between this file's own delete-target and rename-temp-into-place steps,
     * on a *previous* export of this exact [fileName], leaves a `<fileName>.tmp` holding the
     * last complete write and no file at `fileName` at all. Must run before anything else in
     * [write] touches this name, since a fresh export creating a brand-new target at
     * [fileName] would otherwise mask that the recovery was ever needed. If a real target
     * *does* still exist alongside the temp, the crash was earlier than that window (or two
     * attempts overlapped) — the target is authoritative there and the temp is discarded, not
     * promoted, since it may be an incomplete write that never reached the short-write check.
     */
    private fun recoverStaleTempFile(folder: DocumentFile, fileName: String) {
        val temp = folder.findFile("$fileName.tmp") ?: return
        if (folder.findFile(fileName) == null) {
            runCatching { temp.renameTo(fileName) }
        } else {
            runCatching { temp.delete() }
        }
    }

    /** Reported length is 0 on providers that don't track size; not an answer, so not a failure. */
    private const val UNKNOWN_LENGTH = 0L

    /**
     * Whether [reported] proves the write was cut short. Deliberately one-sided: only a
     * length that is *positively* less than expected counts. Providers that report 0 or
     * refuse to answer must not be read as failures, or every export on them would be
     * rejected and retried forever, and a note that is genuinely saved would show as missing.
     */
    internal fun isShortWrite(reported: Long, expected: Int): Boolean =
        reported > UNKNOWN_LENGTH && reported < expected.toLong()
}
