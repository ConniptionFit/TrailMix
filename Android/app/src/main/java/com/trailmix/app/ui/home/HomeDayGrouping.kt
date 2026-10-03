package com.trailmix.app.ui.home

import com.trailmix.app.data.model.SummaryTemplate
import com.trailmix.app.data.model.TemplateOptions
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row Home's list actually renders: either a day header or a note. */
sealed class HomeListItem {
    data class DayHeader(val label: String, val noteCount: Int) : HomeListItem()
    data class NoteRow(val note: HomeNote) : HomeListItem()
}

/**
 * H2: the list is always grouped by day, with the note count on every header. [notes] is
 * assumed already sorted newest-first (Home's own order), so a calendar day is always one
 * contiguous run. [nowMs] is a parameter so "Today" and "Yesterday" can be tested.
 */
fun groupByDay(notes: List<HomeNote>, nowMs: Long = System.currentTimeMillis()): List<HomeListItem> {
    if (notes.isEmpty()) return emptyList()
    val calendar = Calendar.getInstance()
    fun dayKey(epochMs: Long): Int {
        calendar.timeInMillis = epochMs
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }

    val result = ArrayList<HomeListItem>(notes.size + notes.size / 2)
    var index = 0
    while (index < notes.size) {
        val start = index
        val key = dayKey(notes[start].note.createdAtEpochMs)
        while (index < notes.size && dayKey(notes[index].note.createdAtEpochMs) == key) index++
        val dayNotes = notes.subList(start, index)
        result += HomeListItem.DayHeader(
            label = dayLabel(dayNotes.first().note.createdAtEpochMs, nowMs),
            noteCount = dayNotes.size,
        )
        dayNotes.forEach { result += HomeListItem.NoteRow(it) }
    }
    return result
}

/** "Today", "Yesterday", otherwise "Mon, Sep 28". Locale and zone are read live. */
fun dayLabel(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    fun key(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }
    val today = key(nowMs)
    val yesterday = key(Calendar.getInstance().apply { timeInMillis = nowMs; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
    return when (key(epochMs)) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(epochMs))
    }
}

/** Start time in the user's own clock style, for the row meta line. */
fun timeLabel(epochMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(epochMs))

/**
 * "45 min" for the row meta line; "<1 min" below a minute. Hours kick in at 90 minutes so a
 * keynote reads "1 h 30 min" rather than "90 min".
 */
fun durationShort(durationMs: Long): String {
    val min = (durationMs / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "<1 min"
        min < 90 -> "$min min"
        min % 60 == 0L -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }
}

/**
 * The template a note was built with, for the row meta line. A stored custom template shows
 * its own name; AUTO shows nothing, because what it resolved to is not recorded and "Auto"
 * would say nothing about the note.
 */
fun templateName(stored: String?): String? = when {
    stored == null -> null
    stored.startsWith(TemplateOptions.customStored("")) -> stored.removePrefix(TemplateOptions.customStored("")).ifBlank { null }
    else -> SummaryTemplate.fromStored(stored).takeIf { it != SummaryTemplate.AUTO }?.label
}
