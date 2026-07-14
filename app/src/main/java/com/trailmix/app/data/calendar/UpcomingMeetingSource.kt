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

data class UpcomingMeeting(
    val title: String,
    val timeLabel: String,
    val beginEpochMs: Long,
    val endEpochMs: Long,
) {
    val minutesUntilStart: Long
        get() = (beginEpochMs - System.currentTimeMillis()) / 60_000

    fun isOngoingAt(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs in beginEpochMs..endEpochMs
}

/**
 * Reads upcoming calendar events. Strictly read-only and opt-in: nothing is
 * queried until the user grants READ_CALENDAR from the Home card.
 */
@Singleton
class UpcomingMeetingSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun nextMeeting(): UpcomingMeeting? =
        queryWindow(TimeUnit.HOURS.toMillis(24), limit = 1).firstOrNull()

    /** Upcoming (and currently ongoing) non-all-day events over the next [days]. */
    suspend fun listUpcoming(days: Int = 7): List<UpcomingMeeting> =
        queryWindow(TimeUnit.DAYS.toMillis(days.toLong()), limit = 50)

    /** The event happening right now, if any — used to tag capture metadata. */
    suspend fun currentEvent(): UpcomingMeeting? {
        val now = System.currentTimeMillis()
        return queryWindow(TimeUnit.HOURS.toMillis(1), limit = 10)
            .firstOrNull { it.isOngoingAt(now) }
    }

    private suspend fun queryWindow(windowMs: Long, limit: Int): List<UpcomingMeeting> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(now.toString())
                .appendPath((now + windowMs).toString())
                .build()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
            )
            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    "${CalendarContract.Instances.ALL_DAY} = 0",
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            val begin = cursor.getLong(1)
                            add(
                                UpcomingMeeting(
                                    title = cursor.getString(0)?.takeIf { it.isNotBlank() }
                                        ?: "Untitled event",
                                    timeLabel = timeLabel(begin, now),
                                    beginEpochMs = begin,
                                    endEpochMs = cursor.getLong(2),
                                ),
                            )
                        }
                    }
                }.orEmpty()
            }.getOrDefault(emptyList())
        }

    private fun timeLabel(beginMs: Long, nowMs: Long): String {
        val sameDay = SimpleDateFormat("yyyyDDD", Locale.ROOT).let {
            it.format(Date(beginMs)) == it.format(Date(nowMs))
        }
        val pattern = if (sameDay) "h:mm a" else "EEE h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(beginMs))
    }
}
