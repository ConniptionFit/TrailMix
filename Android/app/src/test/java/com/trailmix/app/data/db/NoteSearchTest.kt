package com.trailmix.app.data.db

import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        transcriptJson: String = "[]",
    ) = NoteEntity(
        title = title,
        segmentsJson = "[]",
        transcriptJson = transcriptJson,
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

    // ── UX-17: the verbatim transcript is searchable too ─────────────────────
    //
    // The body is a condensed summary, so a name said once in a long call is very often
    // absent from it. In an app whose whole job is capturing what was said, not being able
    // to find your own words is the worse failure.

    /**
     * A note whose transcript says things the summary never mentions. Built through the real
     * [TranscriptJson] encoder rather than hand-written JSON, so the fixture can't drift from
     * the codec's actual key names.
     */
    private fun withTranscript(vararg lines: String) = note(
        transcriptJson = TranscriptJson.encode(
            lines.mapIndexed { i, text -> TranscriptLine(label = "0:${10 + i}", text = text) },
        ),
    )

    @Test
    fun `finds a word that is only in the transcript`() {
        val n = withTranscript("We should call the Henderson contract done by Friday.")
        assertFalse("precondition: not in the summary", NoteSearch.matches(note(), "henderson"))
        assertTrue(NoteSearch.matches(n, "henderson"))
    }

    @Test
    fun `transcript search is case-insensitive`() {
        val n = withTranscript("The Henderson contract.")
        assertTrue(NoteSearch.matches(n, "HENDERSON"))
    }

    /** The mixed case: one token from the summary, one only from the transcript. */
    @Test
    fun `tokens may be satisfied across body and transcript together`() {
        val n = withTranscript("The Henderson contract needs a signature.")
        assertTrue(NoteSearch.matches(n, "roadmap henderson"))
        assertTrue(NoteSearch.matches(n, "henderson roadmap"))
    }

    @Test
    fun `a token in neither body nor transcript still fails`() {
        val n = withTranscript("The Henderson contract needs a signature.")
        assertFalse(NoteSearch.matches(n, "henderson budget"))
        assertFalse(NoteSearch.matches(n, "kubernetes"))
    }

    @Test
    fun `transcript composes with date search`() {
        val n = withTranscript("The Henderson contract.")
        assertTrue(NoteSearch.matches(n, "henderson 7/18"))
        assertFalse(NoteSearch.matches(n, "henderson 7/19"))
    }

    /** Multi-line transcripts must not let a match straddle two separate lines. */
    @Test
    fun `separate transcript lines are not concatenated into false matches`() {
        val n = withTranscript("ending with alpha", "beta starts here")
        assertTrue(NoteSearch.matches(n, "alpha"))
        assertTrue(NoteSearch.matches(n, "beta"))
        assertFalse(NoteSearch.matches(n, "alphabeta"))
    }

    @Test
    fun `an empty transcript is harmless`() {
        assertFalse(NoteSearch.matches(note(), "henderson"))
        assertTrue(NoteSearch.matches(note(), "roadmap"))
    }

    // ── UX-19/UX-20: the specific moment a transcript match came from ─────────

    @Test
    fun `firstMatchingLine finds the line containing the token, in transcript order`() {
        val lines = listOf(
            TranscriptLine("0:10", "We opened with the roadmap."),
            TranscriptLine("0:24", "The Henderson contract needs a signature."),
            TranscriptLine("0:31", "Henderson again, for good measure."),
        )
        val match = NoteSearch.firstMatchingLine(listOf("henderson"), lines)
        assertEquals("0:24", match?.label)
    }

    @Test
    fun `firstMatchingLine is null for no tokens or no match`() {
        val lines = listOf(TranscriptLine("0:10", "We opened with the roadmap."))
        assertNull(NoteSearch.firstMatchingLine(emptyList(), lines))
        assertNull(NoteSearch.firstMatchingLine(listOf("kubernetes"), lines))
    }
}
