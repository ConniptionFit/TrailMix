package com.trailmix.app.ui.home

import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NoteSearch
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UX-18: Home's derived-data cache. These tests are almost entirely about *how much work*
 * the index does, because that is the whole point of it — the results are supposed to be
 * identical to the uncached path, and `resultsMatch…` pins exactly that.
 */
class HomeNoteIndexTest {

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

    /** July 18 2026, 2:30 PM local. */
    private fun epoch(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 18, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun note(
        id: Long,
        title: String = "Weekly sync",
        body: String = "Discussed the roadmap and next steps",
        meetingTitle: String? = null,
        transcript: String = "",
    ) = NoteEntity(
        id = id,
        title = title,
        segmentsJson = "[]",
        transcriptJson = if (transcript.isBlank()) {
            "[]"
        } else {
            TranscriptJson.encode(listOf(TranscriptLine(label = "Speaker", text = transcript)))
        },
        typedFragments = "",
        durationMs = 60_000,
        createdAtEpochMs = epoch(),
        bodyOverride = body,
        meetingTitle = meetingTitle,
    )

    private fun library() = listOf(
        note(1, title = "Weekly sync", body = "Roadmap and next steps"),
        note(2, title = "Design review", body = "Typography pass", meetingTitle = "Design review"),
        note(3, title = "Budget call", body = "Q3 numbers", transcript = "Henderson raised the forecast"),
    )

    // ── The results must not change, only the cost ──────────────────────────

    @Test
    fun `results match the uncached search for every kind of query`() {
        val notes = library()
        val queries = listOf(
            "", "   ", "weekly", "WEEKLY", "roadmap", "budget", "henderson",
            "weekly roadmap", "weekly budget", "jul 18", "7/18/2026", "2026-07-18",
            "henderson 7/18", "kubernetes", "design typography",
        )
        for (query in queries) {
            val expected = notes.filter { NoteSearch.matches(it, query) }.map { it.id }
            val actual = HomeNoteIndex().filter(notes, query, meetingsOnly = false).map { it.id }
            assertEquals("query: '$query'", expected, actual)
        }
    }

    @Test
    fun `a cached index returns the same results as a cold one`() {
        val notes = library()
        val warm = HomeNoteIndex()
        // Walk the query the way a user types it, so every result below comes from a cache
        // populated by the previous keystroke.
        listOf("h", "he", "hen", "hend").forEach { warm.filter(notes, it, false) }

        assertEquals(
            HomeNoteIndex().filter(notes, "hend", false).map { it.id },
            warm.filter(notes, "hend", false).map { it.id },
        )
    }

    @Test
    fun `newest-first order is preserved`() {
        val notes = library()
        val ids = HomeNoteIndex().filter(notes, "", false).map { it.id }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `the meetings-only filter composes with search`() {
        val notes = library()
        val index = HomeNoteIndex()
        assertEquals(listOf(2L), index.filter(notes, "", meetingsOnly = true).map { it.id })
        assertEquals(listOf(2L), index.filter(notes, "typography", meetingsOnly = true).map { it.id })
        assertTrue(index.filter(notes, "roadmap", meetingsOnly = true).isEmpty())
    }

    @Test
    fun `the transcript fallback still finds words the summary never had`() {
        val notes = library()
        assertFalse("precondition: not in any summary", NoteSearch.matches(notes[0], "henderson"))
        assertEquals(listOf(3L), HomeNoteIndex().filter(notes, "henderson", false).map { it.id })
    }

    @Test
    fun `the display strings are what the entity itself would have produced`() {
        val notes = library()
        val rows = HomeNoteIndex().filter(notes, "", false)
        rows.forEachIndexed { i, row ->
            assertEquals(notes[i].preview, row.preview)
        }
        // The row subtitle is the UX-12 format, in the pinned locale.
        assertEquals("Jul 18, 2026 · 2:30 PM", rows[0].createdLabel)
    }

    // ── The cost, which is the actual subject ───────────────────────────────

    @Test
    fun `an empty query derives no text at all`() {
        val index = HomeNoteIndex()
        index.filter(library(), "", false)
        assertEquals("nothing needs searching when nothing was typed", 0, index.summaryBuilds)
        assertEquals(0, index.transcriptBuilds)
    }

    @Test
    fun `typing does not re-derive text for notes that have not changed`() {
        val notes = library()
        val index = HomeNoteIndex()

        // Ten keystrokes over three notes. Before UX-18 this was 30 derivations.
        listOf("r", "ro", "roa", "road", "roadm", "roadma", "roadmap", "roadmap ", "roadmap n", "roadmap ne")
            .forEach { index.filter(notes, it, false) }

        assertEquals("one derivation per note, not per keystroke", 3, index.summaryBuilds)
    }

    @Test
    fun `editing a note re-derives that note and only that note`() {
        val notes = library()
        val index = HomeNoteIndex()
        index.filter(notes, "roadmap", false)
        assertEquals(3, index.summaryBuilds)

        // Room hands out a fresh instance for the changed row and the same instances for
        // the rest — which is exactly what the reference-identity check keys on.
        val edited = notes.toMutableList().apply { this[1] = this[1].copy(title = "Design review v2") }
        index.filter(edited, "roadmap", false)

        assertEquals("only the edited note is re-derived", 4, index.summaryBuilds)
    }

    @Test
    fun `a note that matches on its summary never decodes its transcript`() {
        val index = HomeNoteIndex()
        index.filter(library(), "roadmap", false)
        // Only note 1 matches "roadmap" on its body. Notes 2 and 3 miss, so they fall
        // through to the transcript and pay for the decode; note 1 must not.
        assertEquals("the matching note must not pay for its transcript", 2, index.transcriptBuilds)
    }

    @Test
    fun `a filtered-out note never builds its display strings`() {
        val index = HomeNoteIndex()
        val rows = index.filter(library(), "roadmap", false)
        assertEquals(1, rows.size)
        assertEquals("only the surviving row is formatted", 1, index.displayBuilds)
    }

    @Test
    fun `display strings survive keystrokes too`() {
        val notes = library()
        val index = HomeNoteIndex()
        repeat(5) { index.filter(notes, "", false) }
        assertEquals("three notes shown five times, formatted once each", 3, index.displayBuilds)
    }

    @Test
    fun `entries for deleted notes are dropped`() {
        val notes = library()
        val index = HomeNoteIndex()
        index.filter(notes, "sync", false)
        assertEquals(3, index.cachedNotes)

        index.filter(notes.take(1), "sync", false)
        assertEquals("the cache tracks the library, it does not grow forever", 1, index.cachedNotes)
    }

    @Test
    fun `a system language change re-derives the date spellings`() {
        val notes = library()
        val index = HomeNoteIndex()
        index.filter(notes, "jul", false)
        assertEquals(3, index.summaryBuilds)

        // The ViewModel outlives the Activity recreation a locale change causes, so the
        // cache has to notice by itself. Asserting the *rebuild* rather than the French
        // month name keeps this independent of the JDK's CLDR data.
        Locale.setDefault(Locale.FRANCE)
        index.filter(notes, "jul", false)
        assertEquals("dates render per locale, so they cannot be reused across one", 6, index.summaryBuilds)
    }

    // ── UX-19/UX-20: the specific moment a transcript-only match came from ────

    @Test
    fun `a note that matched via the transcript carries the matching line`() {
        val notes = library() // note 3 matches "henderson" only in its transcript
        val result = HomeNoteIndex().filter(notes, "henderson", meetingsOnly = false)
        assertEquals(1, result.size)
        assertEquals("Henderson raised the forecast", result.single().matchedMoment?.text)
    }

    @Test
    fun `a note that matched via the summary alone carries no moment`() {
        val notes = library()
        val result = HomeNoteIndex().filter(notes, "roadmap", meetingsOnly = false)
        assertEquals(1, result.size)
        assertEquals(null, result.single().matchedMoment)
    }

    @Test
    fun `an empty query carries no moment for anyone`() {
        val notes = library()
        val result = HomeNoteIndex().filter(notes, "", meetingsOnly = false)
        assertTrue(result.all { it.matchedMoment == null })
    }
}
