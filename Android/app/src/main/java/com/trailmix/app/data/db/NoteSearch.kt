package com.trailmix.app.data.db

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home search (UX-13, v1.8.0): keyword + date matching over the note list, pure and
 * in-memory (the whole library is already streamed to the Home list — no FTS needed at
 * this scale). Every whitespace-separated token in the query must match somewhere in the
 * note: title, body, meeting title, attendee names, or the note's date rendered in several
 * common spellings ("jul 18", "july 18 2026", "7/18", "7/18/2026", "2026-07-18") so typing
 * a date the way you'd say it just works.
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
        return tokens.all { it in haystack }
    }

    private val DATE_PATTERNS = listOf("MMM d", "MMMM d", "MMM d yyyy", "M/d", "M/d/yyyy", "yyyy-MM-dd", "h:mm a")

    private fun dateStrings(epochMs: Long): String {
        val date = Date(epochMs)
        return DATE_PATTERNS.joinToString("\n") {
            SimpleDateFormat(it, Locale.getDefault()).format(date)
        }
    }
}
