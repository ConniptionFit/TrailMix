package com.trailmix.app.ui.meetings

import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.ui.home.dayLabel
import java.util.Calendar

/** One meeting row and the note already made for it, if any. */
data class MeetingRow(val meeting: UpcomingMeeting, val noteId: Long?)

sealed class MeetingsItem {
    data class Day(val label: String) : MeetingsItem()
    data class Row(val row: MeetingRow) : MeetingsItem()
}

/**
 * M1: "has a note". Notes only store the meeting title, not the calendar event id, so a note
 * counts for a meeting when it carries the same title and was started the same day. A repeat
 * of a daily meeting on a later day does not match an earlier day's note.
 */
fun noteIdFor(meeting: UpcomingMeeting, notes: List<NoteEntity>): Long? {
    val day = dayKey(meeting.beginEpochMs)
    return notes.firstOrNull { it.meetingTitle == meeting.title && dayKey(it.createdAtEpochMs) == day }?.id
}

/** Meetings grouped under a header per calendar day, in the order given (already by start time). */
fun groupMeetingsByDay(
    meetings: List<UpcomingMeeting>,
    notes: List<NoteEntity>,
    nowMs: Long = System.currentTimeMillis(),
): List<MeetingsItem> {
    val result = ArrayList<MeetingsItem>(meetings.size + 7)
    var lastDay = Int.MIN_VALUE
    meetings.forEach { meeting ->
        val day = dayKey(meeting.beginEpochMs)
        if (day != lastDay) {
            result += MeetingsItem.Day(dayLabel(meeting.beginEpochMs, nowMs))
            lastDay = day
        }
        result += MeetingsItem.Row(MeetingRow(meeting, noteIdFor(meeting, notes)))
    }
    return result
}

private fun dayKey(epochMs: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = epochMs }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}
