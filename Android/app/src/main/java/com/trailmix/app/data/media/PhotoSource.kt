package com.trailmix.app.data.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MatchedPhoto(
    val uri: Uri,
    val displayName: String,
    val takenAtEpochMs: Long,
)

/**
 * Finds photos taken during a note's session, for the photo-export feature. Strictly
 * read-only and opt-in — nothing is queried until the user grants the media-images
 * permission from the export/share sheet's photo section, mirroring
 * [com.trailmix.app.data.calendar.UpcomingMeetingSource]'s exact pattern (same opt-in
 * shape, same fail-soft "never throws, empty on any problem" style).
 *
 * No network path is touched, no photo is ever scanned in the background — a query only
 * happens when the user actively opens that section.
 */
@Singleton
class PhotoSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Photos whose capture time falls within [startEpochMs]..[endEpochMs], padded by
     * [bufferMs] on each side to catch a photo taken just before the note started or just
     * after it was saved. Falls back to `DATE_ADDED` when `DATE_TAKEN` is null — some camera
     * apps and screenshots don't populate it. Empty on missing permission or any failure;
     * this never throws.
     */
    suspend fun photosBetween(
        startEpochMs: Long,
        endEpochMs: Long,
        bufferMs: Long = DEFAULT_BUFFER_MS,
    ): List<MatchedPhoto> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val (selection, args) = buildSelection(startEpochMs - bufferMs, endEpochMs + bufferMs)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { cursor ->
                buildList {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val taken = cursor.getLong(takenCol).takeIf { it > 0 }
                            ?: TimeUnit.SECONDS.toMillis(cursor.getLong(addedCol))
                        add(
                            MatchedPhoto(
                                uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                                displayName = cursor.getString(nameCol) ?: "IMG_$id",
                                takenAtEpochMs = taken,
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    companion object {
        val DEFAULT_BUFFER_MS = TimeUnit.MINUTES.toMillis(2)

        /**
         * Pure and separately testable from the actual `ContentResolver.query` call (which
         * needs a real device/emulator, see `androidTest`) — this is just string/arg building.
         */
        internal fun buildSelection(startEpochMs: Long, endEpochMs: Long): Pair<String, Array<String>> {
            val selection = "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
                "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND " +
                "${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)"
            val args = arrayOf(
                startEpochMs.toString(),
                endEpochMs.toString(),
                TimeUnit.MILLISECONDS.toSeconds(startEpochMs).toString(),
                TimeUnit.MILLISECONDS.toSeconds(endEpochMs).toString(),
            )
            return selection to args
        }
    }
}
