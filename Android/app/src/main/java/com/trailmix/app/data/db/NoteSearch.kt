package com.trailmix.app.data.db

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home search (UX-13, v1.8.0): keyword + date matching over the note list, pure and
 * in-memory (the whole library is already streamed to the Home list — no FTS needed at
 * this scale). Every whitespace-separated token in the query must match somewhere in the
 * note: title, body, meeting title, attendee names, the note's date rendered in several
 * common spellings ("jul 18", "july 18 2026", "7/18", "7/18/2026", "2026-07-18") so typing
 * a date the way you'd say it just works — or, since UX-17, the **verbatim transcript**.
 *
 * Scale note: this stays in-memory and FTS-free on the assumption of a personal-sized
 * library (tens to low hundreds of notes). The transcript lookup is deliberately lazy and
 * last (see [matches]); if this ever feels slow, the fix is a stored plain-text search
 * column, not decoding more eagerly.
 */
object NoteSearch {
    fun matches(note: NoteEntity, query: String): Boolean {
        val tokens = query.trim().lowercase(Locale.getDefault())
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true

        val haystack = buildString {
            append(note.title).append('\n')
            append(note.displayBody).append('\n')
            note.meetingTitle?.let { append(it).append('\n') }
            note.attendees.forEach { append(it).append('\n') }
            append(dateStrings(note.createdAtEpochMs))
        }.lowercase(Locale.getDefault())

        // UX-17: fall through to the transcript for anything the summary didn't cover.
        //
        // The body is a *condensed* view — a name said once in a 40-minute call very often
        // isn't in it. Not finding your own words in an app whose whole job is capturing them
        // is the worse failure, so the verbatim record is searched too.
        //
        // Only the tokens that actually missed are re-tested, and only then is the transcript
        // decoded: `NoteEntity.transcript` parses JSON on every access, so decoding every note
        // on every keystroke would be the obvious way to make search feel broken. A note that
        // already matches on title or body costs nothing extra.
        val unmatched = tokens.filterNot { it in haystack }
        if (unmatched.isEmpty()) return true

        val transcript = note.transcript
            .joinToString("\n") { it.text }
            .lowercase(Locale.getDefault())
        return unmatched.all { it in transcript }
    }

    private val DATE_PATTERNS = listOf("MMM d", "MMMM d", "MMM d yyyy", "M/d", "M/d/yyyy", "yyyy-MM-dd", "h:mm a")

    private fun dateStrings(epochMs: Long): String {
        val date = Date(epochMs)
        return DATE_PATTERNS.joinToString("\n") {
            SimpleDateFormat(it, Locale.getDefault()).format(date)
        }
    }
}
