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

    private val now = epoch(14, 20)

    @Test
    fun `every day gets a header with its note count`() {
        val notes = listOf(
            homeNote(4, epoch(16, 10)),
            homeNote(3, epoch(14, 16)),
            homeNote(2, epoch(14, 9)),
            homeNote(1, epoch(10, 9)),
        )

        val items = groupByDay(notes, nowMs = epoch(20, 12))

        assertEquals(7, items.size) // 3 headers + 4 rows
        assertEquals(listOf(1, 2, 1), items.filterIsInstance<HomeListItem.DayHeader>().map { it.noteCount })
        // Newest-first order must survive grouping.
        assertEquals(listOf(4L, 3L, 2L, 1L), items.filterIsInstance<HomeListItem.NoteRow>().map { it.note.id })
    }

    @Test
    fun `headers read Today, Yesterday, then the weekday and date`() {
        val notes = listOf(
            homeNote(3, epoch(14, 16)),
            homeNote(2, epoch(13, 9)),
            homeNote(1, epoch(10, 9)),
        )

        val labels = groupByDay(notes, nowMs = now).filterIsInstance<HomeListItem.DayHeader>().map { it.label }

        assertEquals("Today", labels[0])
        assertEquals("Yesterday", labels[1])
        assertTrue("expected a weekday and date, got '${labels[2]}'", labels[2].contains("Aug 10"))
    }

    @Test
    fun `notes at midnight boundaries on adjacent days are not merged into one group`() {
        val notes = listOf(
            homeNote(2, epoch(15, 0)),  // 12:00 AM on the 15th
            homeNote(1, epoch(14, 23)), // 11:00 PM on the 14th
        )

        val headers = groupByDay(notes, nowMs = now).filterIsInstance<HomeListItem.DayHeader>()

        assertEquals(2, headers.size)
        assertTrue(headers.all { it.noteCount == 1 })
    }

    @Test
    fun `an empty list produces nothing`() {
        assertEquals(emptyList<HomeListItem>(), groupByDay(emptyList()))
    }

    @Test
    fun `durations read in minutes, then hours from ninety minutes`() {
        assertEquals("<1 min", durationShort(30_000))
        assertEquals("45 min", durationShort(45 * 60_000L))
        assertEquals("1 h 30 min", durationShort(90 * 60_000L))
        assertEquals("2 h", durationShort(120 * 60_000L))
    }

    @Test
    fun `template names come from built-ins and custom templates, never AUTO`() {
        assertEquals(null, templateName(null))
        assertEquals(null, templateName("AUTO"))
        assertEquals("Pitch review", templateName("custom:Pitch review"))
        assertEquals(com.trailmix.app.data.model.SummaryTemplate.NONE.label, templateName("NONE"))
    }
}
