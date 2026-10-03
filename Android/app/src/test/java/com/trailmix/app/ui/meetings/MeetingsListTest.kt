package com.trailmix.app.ui.meetings

import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.db.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class MeetingsListTest {

    private fun at(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun meeting(title: String, day: Int, hour: Int) = UpcomingMeeting(
        title = title,
        timeLabel = "",
        beginEpochMs = at(day, hour),
        endEpochMs = at(day, hour) + 3_600_000,
        eventId = (day * 100 + hour).toLong(),
    )

    private fun note(id: Long, title: String?, day: Int, hour: Int) = NoteEntity(
        id = id,
        title = "n",
        segmentsJson = "[]",
        transcriptJson = "[]",
        typedFragments = "",
        durationMs = 0,
        createdAtEpochMs = at(day, hour),
        meetingTitle = title,
    )

    @Test
    fun `a note with the same title on the same day counts as having a note`() {
        val notes = listOf(note(7, "Design review", 5, 10))
        assertEquals(7L, noteIdFor(meeting("Design review", 5, 11), notes))
    }

    @Test
    fun `the same title on another day does not match`() {
        val notes = listOf(note(7, "Standup", 4, 9))
        assertNull(noteIdFor(meeting("Standup", 5, 9), notes))
    }

    @Test
    fun `a different title on the same day does not match`() {
        val notes = listOf(note(7, "Planning", 5, 9))
        assertNull(noteIdFor(meeting("Standup", 5, 9), notes))
    }

    @Test
    fun `meetings get one header per day`() {
        val items = groupMeetingsByDay(
            listOf(meeting("A", 5, 9), meeting("B", 5, 14), meeting("C", 6, 9)),
            emptyList(),
            nowMs = at(5, 8),
        )
        assertEquals(5, items.size)
        assertEquals(2, items.count { it is MeetingsItem.Day })
        assertEquals("Today", (items[0] as MeetingsItem.Day).label)
    }
}
