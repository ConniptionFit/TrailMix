package com.trailmix.app.data.export

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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

    private fun copyOne(context: Context, photosFolder: DocumentFile, uriString: String): ExportedPhoto? =
        runCatching {
            val sourceUri = Uri.parse(uriString)
            val (displayName, takenAtEpochMs) = readMetadata(context, sourceUri) ?: return@runCatching null
            val fileName = uniqueName(photosFolder, displayName)
            val target = photosFolder.createFile(guessMimeType(fileName), fileName) ?: return@runCatching null

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                } ?: return@runCatching null
            } ?: return@runCatching null

            ExportedPhoto(filename = target.name ?: fileName, takenAtEpochMs = takenAtEpochMs)
        }.getOrNull()

    /** Same [MediaStore] columns [com.trailmix.app.data.media.PhotoSource] queried for the picker. */
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

    /** Avoids clobbering an identically-named photo from a previous export of this note. */
    private fun uniqueName(folder: DocumentFile, desired: String): String {
        if (folder.findFile(desired) == null) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        var candidate: String
        do {
            candidate = "$base-$i$ext"
            i++
        } while (folder.findFile(candidate) != null)
        return candidate
    }

    private fun guessMimeType(fileName: String): String = when {
        fileName.endsWith(".png", ignoreCase = true) -> "image/png"
        fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
        fileName.endsWith(".heic", ignoreCase = true) -> "image/heic"
        else -> "image/jpeg"
    }

    private const val PHOTOS_SUBFOLDER = "photos"
}
