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
 * UX-18 (v1.19.0) split this into pieces a caller can cache. Deriving a note's searchable
 * text costs two JSON decodes and seven [SimpleDateFormat] builds; doing that per note per
 * keystroke is what made a large library feel slow. The text a note contributes only
 * changes when the note does, so [HomeNoteIndex] computes it once per note and reuses it
 * across keystrokes — [matches] here stays pure and does no caching of its own.
 *
 * Scale note: this stays in-memory and FTS-free on the assumption of a personal-sized
 * library (tens to low hundreds of notes). If it ever stops being enough, the fix is a
 * stored plain-text search column, not decoding more eagerly.
 */
object NoteSearch {

    /** Hoisted: [Regex] compilation is not free, and this one never varies. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Split a query into the tokens a note must match. Hoisted out of [matches] so a list
     * filter tokenizes once per query rather than once per note (UX-18).
     */
    fun tokenize(query: String): List<String> =
        query.trim().lowercase(Locale.getDefault())
            .split(WHITESPACE).filter { it.isNotBlank() }

    /**
     * Everything about a note that is searchable *except* the transcript, lowercased and
     * newline-joined. Costs a JSON decode (the body's segments, when there's no hand-edited
     * override) plus the date renderings.
     */
    fun summaryText(note: NoteEntity): String = buildString {
        append(note.title).append('\n')
        append(note.displayBody).append('\n')
        note.meetingTitle?.let { append(it).append('\n') }
        note.attendees.forEach { append(it).append('\n') }
        append(dateStrings(note.createdAtEpochMs))
    }.lowercase(Locale.getDefault())

    /** The verbatim record, lowercased. Costs the transcript JSON decode — by far the
     *  larger of the two, which is why callers should reach for it last. */
    fun transcriptText(note: NoteEntity): String =
        note.transcript.joinToString("\n") { it.text }.lowercase(Locale.getDefault())

    /**
     * The matching rule, against text a caller has already derived (and may have cached).
     *
     * UX-17: anything the summary didn't cover falls through to the transcript. The body is
     * a *condensed* view — a name said once in a 40-minute call very often isn't in it, and
     * not finding your own words in an app whose whole job is capturing them is the worse
     * failure. Only the tokens that actually missed are re-tested.
     *
     * Both haystacks are lambdas so this computes nothing it doesn't need: an empty query
     * (Home's resting state) derives no text at all, and a note matching on its title never
     * pays to decode its transcript.
     */
    fun matches(tokens: List<String>, summary: () -> String, transcript: () -> String): Boolean {
        if (tokens.isEmpty()) return true
        val unmatched = tokens.filterNot { it in summary() }
        if (unmatched.isEmpty()) return true
        val transcriptText = transcript()
        return unmatched.all { it in transcriptText }
    }

    /**
     * One note, one query, nothing cached. The straightforward form — used by tests and by
     * any caller not filtering a whole list. Home goes through [HomeNoteIndex] instead.
     */
    fun matches(note: NoteEntity, query: String): Boolean =
        matches(tokenize(query), { summaryText(note) }, { transcriptText(note) })

    private val DATE_PATTERNS = listOf("MMM d", "MMMM d", "MMM d yyyy", "M/d", "M/d/yyyy", "yyyy-MM-dd", "h:mm a")

    /**
     * The formatters are built per call rather than cached: [SimpleDateFormat] is mutable
     * and not thread-safe, and it captures [Locale.getDefault] at construction, so a shared
     * instance would be both a data race and a stale rendering after a system language
     * change. Building them is only affordable because [summaryText] is now called once per
     * note version instead of once per keystroke.
     */
    private fun dateStrings(epochMs: Long): String {
        val date = Date(epochMs)
        return DATE_PATTERNS.joinToString("\n") {
            SimpleDateFormat(it, Locale.getDefault()).format(date)
        }
    }
}
