package com.trailmix.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NoteSearch
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.speech.CaptureSessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Home in-progress-transcription chip needs to render (CAP-10). */
data class ActiveCaptureUi(val elapsedLabel: String, val meetingTitle: String?, val paused: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val meetingSource: UpcomingMeetingSource,
    private val captureSessionManager: CaptureSessionManager,
) : ViewModel() {

    /** Search query (UX-13) — filters the notes list live; blank shows everything. */
    val searchQuery = MutableStateFlow("")

    /** CAL-05: show only notes linked to a calendar meeting. */
    private val _meetingsOnly = MutableStateFlow(false)
    val meetingsOnly: StateFlow<Boolean> = _meetingsOnly.asStateFlow()

    val notes: StateFlow<List<NoteEntity>> =
        combine(notesRepository.observeNotes(), searchQuery, _meetingsOnly) { all, query, meetings ->
            all.filter { note ->
                (!meetings || note.meetingTitle != null) && NoteSearch.matches(note, query)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many notes sit in Recently deleted (REL-06) — drives the entry row's visibility. */
    val deletedCount: StateFlow<Int> = notesRepository.observeDeletedNotes()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ── Multi-select (UX-10) ────────────────────────────────────────────────

    private val _selectedIds = MutableStateFlow<Set<Long>?>(null)
    /** Null = not in selection mode; a (possibly empty) set = selection mode active. */
    val selectedIds: StateFlow<Set<Long>?> = _selectedIds.asStateFlow()

    fun enterSelectionMode(initialId: Long) {
        _selectedIds.value = setOf(initialId)
    }

    fun toggleSelected(id: Long) {
        val current = _selectedIds.value ?: return
        _selectedIds.value = if (id in current) current - id else current + id
    }

    fun exitSelectionMode() {
        _selectedIds.value = null
    }

    /** Soft-delete every selected note (each recoverable via Recently deleted for 1 day). */
    fun deleteSelected() {
        val ids = _selectedIds.value.orEmpty()
        _selectedIds.value = null
        viewModelScope.launch {
            var fileFailures = 0
            ids.forEach { if (!notesRepository.delete(it).filesDeleted) fileFailures++ }
            val n = ids.size
            _snackbarMessage.tryEmit(
                buildString {
                    append("Moved $n note${if (n == 1) "" else "s"} to Recently deleted")
                    if (fileFailures > 0) append(" ($fileFailures exported cop${if (fileFailures == 1) "y" else "ies"} couldn't be removed)")
                },
            )
        }
    }

    /** Combined Markdown of the selected notes, in list (newest-first) order, for sharing. */
    suspend fun selectedMarkdown(): String {
        val ids = _selectedIds.value.orEmpty()
        return notes.first().filter { it.id in ids }.joinToString("\n\n---\n\n") { it.toMarkdown() }
    }

    private val _upcoming = MutableStateFlow<UpcomingMeeting?>(null)
    val upcoming: StateFlow<UpcomingMeeting?> = _upcoming.asStateFlow()

    private val _calendarGranted = MutableStateFlow(meetingSource.hasPermission())
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    /** Non-null while a capture is recording/merging anywhere in the app — the chip source. */
    val activeCapture: StateFlow<ActiveCaptureUi?> =
        combine(captureSessionManager.hasActiveSession, captureSessionManager.state) { active, state ->
            if (active) ActiveCaptureUi(state.elapsedLabel, state.meetingTitle, state.paused) else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    init {
        refreshUpcoming()
        // REL-06: opportunistic purge of notes past the 1-day recovery window.
        viewModelScope.launch { notesRepository.purgeExpiredDeleted() }
    }

    fun setMeetingsOnly(on: Boolean) {
        _meetingsOnly.value = on
    }

    fun refreshUpcoming() {
        _calendarGranted.value = meetingSource.hasPermission()
        viewModelScope.launch { _upcoming.value = meetingSource.nextMeeting() }
    }

    /** Soft delete (REL-06) + remove the tracked export file (CAP-05). Fail-soft: a file
     * delete failure never blocks the local delete, just surfaces a Snackbar hint. */
    fun deleteNote(id: Long) {
        viewModelScope.launch {
            val result = notesRepository.delete(id)
            _snackbarMessage.tryEmit(
                if (result.filesDeleted) {
                    "Moved to Recently deleted"
                } else {
                    "Moved to Recently deleted; couldn't remove the exported copy"
                },
            )
        }
    }
}
