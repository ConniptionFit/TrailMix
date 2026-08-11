package com.trailmix.app.ui.home

import androidx.annotation.VisibleForTesting
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NoteSearch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** A note plus the strings Home renders that have to be *derived* from it (UX-18). */
data class HomeNote(
    val note: NoteEntity,
    /** First line of the body, one line, already flattened — [NoteEntity.preview]. */
    val preview: String,
    /** "MMM d, yyyy · h:mm a" in the current locale — the UX-12 row subtitle. */
    val createdLabel: String,
) {
    val id: Long get() = note.id
}

/**
 * UX-18 (v1.19.0): the derived-data cache behind Home's list and search.
 *
 * Everything Home shows or searches has to be computed from a [NoteEntity]'s stored
 * columns, and none of it is cheap: the body and attendees are JSON, the transcript is a
 * much larger JSON, and the searchable date spellings cost seven [SimpleDateFormat] builds
 * apiece. Before this, all of it ran again for every note on every keystroke — and on the
 * main thread, because the filter lives in a `combine` collected in `viewModelScope`. At a
 * couple of hundred notes that is thousands of allocations and two JSON parses per note per
 * character typed, which is exactly what a search box must not do.
 *
 * The fix is that a note's derived text only changes when the note does. Entries are keyed
 * by note id and validated by **reference identity** against the entity they were built
 * from: Room hands out a fresh [NoteEntity] instance whenever a row changes, so an
 * unchanged instance is proof the derived text is still current, and a changed one always
 * misses. That makes staleness impossible to get wrong — there is no fingerprint to keep in
 * sync. Keystrokes reuse the same list instance and so hit the cache every time.
 *
 * Everything is computed lazily and independently, because most notes never need most of
 * it: an empty query needs no search text at all, a note that matches on its title never
 * decodes its transcript, and a note filtered out never builds its display strings.
 */
class HomeNoteIndex {

    /**
     * Cheap stand-in for "how dates render right now". Both [Locale] and [TimeZone] are read
     * at format time from process-wide defaults, so a language or travel-induced timezone
     * change has to invalidate the cache — the ViewModel outlives the Activity recreation
     * that a configuration change triggers, so nothing else would.
     */
    private fun formattingKey(): String =
        Locale.getDefault().toString() + "|" + TimeZone.getDefault().id

    private inner class Entry(val source: NoteEntity, val formatting: String) {
        val searchText: String by lazy { summaryBuilds++; NoteSearch.summaryText(source) }
        val transcriptText: String by lazy { transcriptBuilds++; NoteSearch.transcriptText(source) }
        val homeNote: HomeNote by lazy {
            displayBuilds++
            HomeNote(
                note = source,
                preview = source.preview,
                createdLabel = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
                    .format(Date(source.createdAtEpochMs)),
            )
        }
    }

    /**
     * Concurrent because the filter runs on [kotlinx.coroutines.Dispatchers.Default], and
     * while consecutive passes are ordered by the collecting coroutine, that is a subtler
     * guarantee than this map is worth leaning on.
     */
    private val entries = ConcurrentHashMap<Long, Entry>()

    // Test seams (UX-18): the point of this class is *how often* it computes, which is
    // otherwise invisible — the results are identical either way. Tests assert these.
    @VisibleForTesting internal var summaryBuilds = 0
        private set
    @VisibleForTesting internal var transcriptBuilds = 0
        private set
    @VisibleForTesting internal var displayBuilds = 0
        private set

    /** How many notes the cache is currently holding entries for. */
    @VisibleForTesting internal val cachedNotes: Int get() = entries.size

    private fun entryFor(note: NoteEntity, formatting: String): Entry {
        val cached = entries[note.id]
        // Reference identity, deliberately: a new instance means Room re-read the row.
        if (cached != null && cached.source === note && cached.formatting == formatting) return cached
        return Entry(note, formatting).also { entries[note.id] = it }
    }

    /**
     * Home's whole list pipeline: apply the CAL-05 meetings filter and the UX-13 search,
     * and return display models for what survives, newest-first order preserved.
     */
    fun filter(notes: List<NoteEntity>, query: String, meetingsOnly: Boolean): List<HomeNote> {
        // Once per pass, not once per note.
        val tokens = NoteSearch.tokenize(query)
        val formatting = formattingKey()

        val result = ArrayList<HomeNote>(notes.size)
        for (note in notes) {
            if (meetingsOnly && note.meetingTitle == null) continue
            val entry = entryFor(note, formatting)
            if (!NoteSearch.matches(tokens, { entry.searchText }, { entry.transcriptText })) continue
            result += entry.homeNote
        }

        // Drop entries for notes that are gone (deleted, or purged) so the cache tracks the
        // library rather than growing with every note the process has ever seen.
        if (entries.size > notes.size) {
            val live = notes.mapTo(HashSet(notes.size)) { it.id }
            entries.keys.retainAll(live)
        }
        return result
    }
}
