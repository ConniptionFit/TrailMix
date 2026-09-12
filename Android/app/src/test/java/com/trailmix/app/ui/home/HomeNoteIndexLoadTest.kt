package com.trailmix.app.ui.home

import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.model.TranscriptJson
import com.trailmix.app.data.model.TranscriptLine
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 (conference-scale reliability pass): [LongSessionLoadTest][com.trailmix.app.data.ai.LongSessionLoadTest]
 * covers one 90-minute session; this covers what a full multi-session conference *day* does
 * to Home — a single track easily produces 6-8 keynote-length notes before lunch, and the
 * whole point of UX-18's `HomeNoteIndex` was that search must not re-decode every note's
 * transcript on every keystroke. A handful of small notes never exercises that; this does.
 *
 * Deliberately generous bounds, same as [LongSessionLoadTest][com.trailmix.app.data.ai.LongSessionLoadTest] —
 * these are tripwires for a super-linear regression, not benchmarks.
 */
class HomeNoteIndexLoadTest {

    /** A full single-track conference day: 8 keynote-length (90-minute) sessions. */
    private val sessionsPerDay = 8
    private val linesPerSession = 1_200

    private fun daySchedule(): List<NoteEntity> = (0 until sessionsPerDay).map { session ->
        val transcript = (0 until linesPerSession).map { i ->
            val ms = (90 * 60_000L * i) / linesPerSession
            val label = String.format("%d:%02d", ms / 60_000, (ms % 60_000) / 1000)
            TranscriptLine(
                label = label,
                // The word "zephyrine" only appears in session 5's closing minutes — a
                // needle only a real transcript-fallthrough search would find, matching
                // UX-17's own reasoning: a name said once in 90 minutes rarely makes the
                // condensed summary.
                text = if (session == 5 && i == linesPerSession - 3) {
                    "The closing recommendation is to consolidate on zephyrine rollouts."
                } else {
                    "Session $session covered topic ${i % 37} with detail level ${i % 11} " +
                        "and a follow-up assigned to team ${i % 5}."
                },
            )
        }
        NoteEntity(
            id = session.toLong(),
            title = "Track A — Session $session",
            segmentsJson = "[]",
            transcriptJson = TranscriptJson.encode(transcript),
            typedFragments = "",
            durationMs = 90 * 60_000L,
            createdAtEpochMs = 1_785_000_000_000L + session * 2 * 60 * 60_000L,
            bodyOverride = "Summary of session $session.",
        )
    }

    @Test
    fun `a full conference day filters fast even on a query that falls through to every transcript`() {
        val notes = daySchedule()
        val index = HomeNoteIndex()

        // First pass is the expensive one — every note's JSON gets decoded once.
        val cold: Long
        lateinit var result: List<HomeNote>
        cold = measureTimeMillis {
            result = index.filter(notes, "zephyrine", meetingsOnly = false)
        }
        assertEquals("only session 5 says it, in its closing minutes", 1, result.size)
        assertEquals("Track A — Session 5", result.single().note.title)
        assertTrue(
            "the needle is a transcript-only match, so it should carry the matching moment",
            result.single().matchedMoment?.text?.contains("zephyrine") == true,
        )
        assertTrue("cold pass over a full day took ${cold}ms — suspect super-linear cost", cold < 5_000)

        // Every subsequent keystroke on an unchanged library must be near-free — this is
        // UX-18's entire premise, and a full conference day is exactly the scale where a
        // regression back to "decode everything every keystroke" would actually be felt.
        val warm = measureTimeMillis {
            repeat(20) { index.filter(notes, "zephyrine", meetingsOnly = false) }
        }
        assertTrue("20 warm passes took ${warm}ms — suspect the cache is not being hit", warm < 200)
    }

    @Test
    fun `narrowing the query keystroke by keystroke reuses one entry per note, not per keystroke`() {
        val notes = daySchedule()
        val index = HomeNoteIndex()

        // Simulates typing "session 3" one character at a time — the realistic access
        // pattern a search box actually produces, not one query called in a loop.
        val query = "session 3"
        for (end in 1..query.length) {
            index.filter(notes, query.substring(0, end), meetingsOnly = false)
        }

        // One Entry per note across the whole keystroke sequence, however many partial
        // queries touched it — HomeNoteIndexTest already pins the finer-grained "how much
        // work per entry" behavior; this is the conference-day-scale version of the same
        // property: entries don't multiply with keystrokes, only with the library size.
        assertEquals(sessionsPerDay, index.cachedNotes)
    }
}
