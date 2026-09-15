package com.trailmix.app.ui.home

import com.trailmix.app.data.db.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** CAL-06 (conference scale): a multi-session day gets a header, an ordinary day doesn't. */
class HomeDayGroupingTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /** Aug 14 2026, at [hour]:00 local. */
    private fun epoch(day: Int, hour: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.AUGUST, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun homeNote(id: Long, epochMs: Long, title: String = "Session $id") = HomeNote(
        note = NoteEntity(
            id = id,
            title = title,
            segmentsJson = "[]",
            transcriptJson = "[]",
            typedFragments = "",
            durationMs = 90 * 60_000L,
            createdAtEpochMs = epochMs,
        ),
        preview = "",
        createdLabel = "",
    )

    @Test
    fun `a day with three sessions gets one header naming the count`() {
        // Newest-first, matching Home's real order.
        val notes = listOf(
            homeNote(3, epoch(14, 16)),
            homeNote(2, epoch(14, 11)),
            homeNote(1, epoch(14, 9)),
        )

        val items = groupByConferenceDay(notes)

        assertEquals(4, items.size) // 1 header + 3 rows
        val header = items[0] as HomeListItem.DayHeader
        assertEquals(3, header.sessionCount)
        assertTrue("expected an Aug 14 label, got '${header.label}'", header.label.contains("Aug 14"))
        // Newest-first order must survive grouping — the header doesn't reorder anything.
        assertEquals(listOf(3L, 2L, 1L), items.drop(1).map { (it as HomeListItem.NoteRow).note.id })
    }

    @Test
    fun `a day with exactly one note gets no header at all`() {
        val notes = listOf(homeNote(1, epoch(14, 9)))

        val items = groupByConferenceDay(notes)

        assertEquals(1, items.size)
        assertTrue(items[0] is HomeListItem.NoteRow)
    }

    @Test
    fun `separate single-note days stay flat, only the multi-session day gets a header`() {
        val notes = listOf(
            homeNote(4, epoch(16, 10)), // alone on the 16th
            homeNote(3, epoch(14, 16)), // 2 sessions on the 14th
            homeNote(2, epoch(14, 9)),
            homeNote(1, epoch(10, 9)), // alone on the 10th
        )

        val items = groupByConferenceDay(notes)

        // 1 row for the 16th + (1 header + 2 rows) for the 14th + 1 row for the 10th.
        assertEquals(5, items.size)
        assertTrue(items[0] is HomeListItem.NoteRow)
        assertTrue(items[1] is HomeListItem.DayHeader)
        assertEquals(2, (items[1] as HomeListItem.DayHeader).sessionCount)
        assertTrue(items[2] is HomeListItem.NoteRow)
        assertTrue(items[3] is HomeListItem.NoteRow)
        assertTrue(items[4] is HomeListItem.NoteRow)
    }

    @Test
    fun `notes at midnight boundaries on adjacent days are not merged into one group`() {
        val notes = listOf(
            homeNote(2, epoch(15, 0)),  // 12:00 AM on the 15th
            homeNote(1, epoch(14, 23)), // 11:00 PM on the 14th
        )

        val items = groupByConferenceDay(notes)

        assertEquals("two different calendar days, neither has a second session", 2, items.size)
        assertTrue(items.all { it is HomeListItem.NoteRow })
    }

    @Test
    fun `an empty list produces nothing`() {
        assertEquals(emptyList<HomeListItem>(), groupByConferenceDay(emptyList()))
    }
}
