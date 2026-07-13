package com.trailmix.app.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UpcomingMeeting(val title: String, val timeLabel: String)

/**
 * Reads the next calendar event within 24h. Strictly read-only and opt-in:
 * nothing is queried until the user grants READ_CALENDAR from the Home card.
 */
@Singleton
class UpcomingMeetingSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun nextMeeting(): UpcomingMeeting? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val now = System.currentTimeMillis()
        val end = now + TimeUnit.HOURS.toMillis(24)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )
        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.ALL_DAY} = 0",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "Untitled event"
                    val begin = cursor.getLong(1)
                    UpcomingMeeting(
                        title = title,
                        timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(begin)),
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
