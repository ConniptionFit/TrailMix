package com.trailmix.app.data.db

import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** UX-13: keyword + date matching over Home's note list. */
class NoteSearchTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        // NoteSearch renders dates with the default locale; pin it so the
        // month-name assertions ("jul 18") are deterministic on any machine.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    /** July 18 2026, 2:30 PM in the local timezone. */
    private fun epoch(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 18, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun note(
        title: String = "Weekly sync",
        body: String = "Discussed the roadmap and next steps",
        meetingTitle: String? = null,
        attendeesJson: String? = null,
    ) = NoteEntity(
        title = title,
        segmentsJson = "[]",
        transcriptJson = "[]",
        typedFragments = "",
        durationMs = 60_000,
        createdAtEpochMs = epoch(),
        bodyOverride = body,
        meetingTitle = meetingTitle,
        attendeesJson = attendeesJson,
    )

    @Test
    fun `blank query matches everything`() {
        assertTrue(NoteSearch.matches(note(), ""))
        assertTrue(NoteSearch.matches(note(), "   "))
    }

    @Test
    fun `keywords match title and body case-insensitively`() {
        assertTrue(NoteSearch.matches(note(), "WEEKLY"))
        assertTrue(NoteSearch.matches(note(), "roadmap"))
        assertFalse(NoteSearch.matches(note(), "budget"))
    }

    @Test
    fun `all tokens must match - across fields`() {
        assertTrue(NoteSearch.matches(note(), "weekly roadmap"))
        assertFalse(NoteSearch.matches(note(), "weekly budget"))
    }

    @Test
    fun `meeting title and attendees are searchable`() {
        val n = note(meetingTitle = "Q3 Planning", attendeesJson = "[\"Charlie Brown\"]")
        assertTrue(NoteSearch.matches(n, "q3 planning"))
        assertTrue(NoteSearch.matches(n, "charlie"))
        assertFalse(NoteSearch.matches(note(), "charlie"))
    }

    @Test
    fun `dates match in common spellings`() {
        val n = note()
        assertTrue(NoteSearch.matches(n, "jul 18"))
        assertTrue(NoteSearch.matches(n, "july 18"))
        assertTrue(NoteSearch.matches(n, "jul 18 2026"))
        assertTrue(NoteSearch.matches(n, "7/18"))
        assertTrue(NoteSearch.matches(n, "7/18/2026"))
        assertTrue(NoteSearch.matches(n, "2026-07-18"))
        assertFalse(NoteSearch.matches(n, "8/19/2026"))
    }

    @Test
    fun `date and keyword compose`() {
        assertTrue(NoteSearch.matches(note(), "roadmap 7/18"))
        assertFalse(NoteSearch.matches(note(), "budget 7/18"))
    }
}
