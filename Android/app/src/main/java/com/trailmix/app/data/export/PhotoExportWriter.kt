package com.trailmix.app.data.export

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

/**
 * Copies selected MediaStore photos into a `photos/` subfolder beside a note's export, via
 * plain SAF `DocumentFile`/`ContentResolver` reads — never a network SDK, same posture as
 * every other file this app writes. Parallel to [MarkdownExportWriter]'s find-or-create
 * pattern, kept as its own object because photo bytes (not UTF-8 Markdown text) are a
 * different enough write shape to not share that file's helpers.
 */
object PhotoExportWriter {

    /**
     * Best-effort: each photo either copies or is silently skipped (a revoked permission, a
     * since-deleted photo, a SAF failure) — one bad photo must never fail the whole export,
     * which is why this returns only the successes rather than throwing.
     *
     * INT-05: takes the already-resolved note [folder] rather than re-resolving it from a
     * tree URI + name — see [MarkdownExportWriter.writeIntoFolder]'s doc for why.
     */
    fun copyInto(
        context: Context,
        folder: DocumentFile,
        photoUriStrings: List<String>,
    ): List<ExportedPhoto> {
        if (photoUriStrings.isEmpty()) return emptyList()
        val photosFolder = runCatching {
            folder.findFile(PHOTOS_SUBFOLDER)?.takeIf { it.isDirectory }
                ?: folder.createDirectory(PHOTOS_SUBFOLDER)
        }.getOrNull() ?: return emptyList()

        return photoUriStrings.mapNotNull { uriString -> copyOne(context, photosFolder, uriString) }
    }

    /**
     * INT-06 (2026-09-19): [copyInto] runs on every re-export (merge/edit/recipe-run,
     * [com.trailmix.app.data.db.NotesRepository.exportIfConfigured]'s automatic trigger), not
     * just the first one — but [uniqueName] (below, since removed) always found the previous
     * export's file already occupying the *desired* name and uniquified around it, so every
     * re-export of an unchanged photo set duplicated every photo again: `photo.jpg`, then
     * `photo-1.jpg`, then `photo-2.jpg`, unbounded growth in the user's export folder for
     * content that never actually changed.
     *
     * The fix is to make the filename an *identity*, not just a display label, so a re-export
     * can recognize "this photo is already here" instead of always finding a free name.
     * [Uri.getLastPathSegment] on a MediaStore image content URI is that photo's stable row id
     * — the same photo re-selected across app launches resolves to the same id, so a name built
     * from it collides on purpose with its own prior copy (and only its own: two different
     * photos never share a MediaStore row id). When that exact name is already present, this
     * skips the read+copy entirely rather than writing a byte-identical duplicate beside it.
     */
    private fun copyOne(context: Context, photosFolder: DocumentFile, uriString: String): ExportedPhoto? =
        runCatching {
            val sourceUri = uriString.toUri()
            val mediaStoreId = sourceUri.lastPathSegment
            val (displayName, takenAtEpochMs) = readMetadata(context, sourceUri) ?: return@runCatching null
            val fileName = identityName(displayName, mediaStoreId)

            photosFolder.findFile(fileName)?.takeIf { it.isFile }?.let { existing ->
                return@runCatching ExportedPhoto(filename = existing.name ?: fileName, takenAtEpochMs = takenAtEpochMs)
            }

            val target = photosFolder.createFile(guessMimeType(fileName), fileName) ?: return@runCatching null
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: return@runCatching null
            } ?: return@runCatching null

            ExportedPhoto(filename = target.name ?: fileName, takenAtEpochMs = takenAtEpochMs)
        }.getOrNull()

    /** Builds an identity-qualified filename — see [copyOne]'s doc. Falls back to
     *  [uniqueName]'s old collision-avoidance behavior only when the source URI has no
     *  MediaStore row id to key on (not expected in practice for an image content URI, but
     *  never worth failing the whole export over). */
    @VisibleForTesting
    internal fun identityName(displayName: String, mediaStoreId: String?): String {
        if (mediaStoreId == null) return displayName
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        return "$base-$mediaStoreId$ext"
    }

    /** Same [MediaStore] columns [com.trailmix.app.data.media.PhotoSource] queried for the picker. */
    // BLD-03: Lint's Recycle check flags the Cursor below as never closed — it doesn't credit
    // Kotlin's `.use { }` (a try/finally close() under the hood) as equivalent to a manual
    // close() call. Confirmed the check itself is what's stale here, not this code.
    @SuppressLint("Recycle")
    private fun readMetadata(context: Context, uri: Uri): Pair<String, Long>? = runCatching {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                ?: "photo_${System.currentTimeMillis()}.jpg"
            val taken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                .takeIf { it > 0 }
                ?: (cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)) * 1000)
            name to taken
        }
    }.getOrNull()

    private fun guessMimeType(fileName: String): String = when {
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        fileName.endsWith(".heic", ignoreCase = true) -> "image/heic"
        else -> "image/jpeg"
    }

    private const val PHOTOS_SUBFOLDER = "photos"
}
