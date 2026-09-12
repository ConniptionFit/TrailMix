package com.trailmix.app.ui.home

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row Home's list actually renders — either a day header or a note. */
sealed class HomeListItem {
    data class DayHeader(val label: String, val sessionCount: Int) : HomeListItem()
    data class NoteRow(val note: HomeNote) : HomeListItem()
}

/**
 * Conference-day grouping: a single-track conference easily produces several keynote-length
 * notes before lunch, and a flat list gives no sense that "these three are today's sessions"
 * versus three unrelated notes from three different weeks. [notes] is assumed already sorted
 * newest-first (Home's own order) — a calendar day is a contiguous range of time, so its notes
 * are always adjacent in that order regardless of how many other days sit around them.
 *
 * A header only appears for a day with more than one note; a day with exactly one is exactly
 * what the flat list already looked like; a header on every day would be reformatting Home
 * for a case (a single ordinary note) this feature isn't about.
 *
 * Pure and Android-free but for the two date-formatting calls, which — like
 * [HomeNoteIndex] — read [Locale]/[Calendar] defaults live rather than caching a formatter,
 * because both can change while the app is running (a language switch, DST) and this is cheap
 * enough (a handful of calls per list rebuild, not per keystroke) that caching would be
 * premature.
 */
fun groupByConferenceDay(notes: List<HomeNote>): List<HomeListItem> {
    if (notes.isEmpty()) return emptyList()
    val calendar = Calendar.getInstance()
    fun dayKey(epochMs: Long): Int {
        calendar.timeInMillis = epochMs
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
    fun dayLabel(epochMs: Long): String =
        SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(epochMs))

    val result = ArrayList<HomeListItem>(notes.size + notes.size / 2)
    var index = 0
    while (index < notes.size) {
        val start = index
        val key = dayKey(notes[start].note.createdAtEpochMs)
        while (index < notes.size && dayKey(notes[index].note.createdAtEpochMs) == key) index++
        val dayNotes = notes.subList(start, index)
        if (dayNotes.size > 1) {
            result += HomeListItem.DayHeader(
                label = dayLabel(dayNotes.first().note.createdAtEpochMs),
                sessionCount = dayNotes.size,
            )
        }
        dayNotes.forEach { result += HomeListItem.NoteRow(it) }
    }
    return result
}
