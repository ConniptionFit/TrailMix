package com.trailmix.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.db.toMarkdown
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.data.speech.PendingJournal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the Home in-progress-transcription chip needs to render (CAP-10). */
data class ActiveCaptureUi(
    val elapsedLabel: String,
    val meetingTitle: String?,
    val paused: Boolean = false,
    /** True while the capture is being merged (or a note rebuilt), so Home shows "Building", not "Recording". */
    val merging: Boolean = false,
)

/** A delete the user can still take back: the ids to restore and how many files failed to go. */
data class UndoableDelete(val ids: List<Long>, val fileFailures: Int)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val meetingSource: UpcomingMeetingSource,
    private val captureSessionManager: CaptureSessionManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Persisted default (Settings) the share sheet's one-off picker starts from. */
    val exportFormat: StateFlow<ExportFormat> = settingsRepository.exportFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportFormat.LLM_OPTIMIZED)

    /** Search query (UX-13) — filters the notes list live; blank shows everything. */
    val searchQuery = MutableStateFlow("")

    /** CAL-05: show only notes linked to a calendar meeting. */
    private val _meetingsOnly = MutableStateFlow(false)
    val meetingsOnly: StateFlow<Boolean> = _meetingsOnly.asStateFlow()

    private val searchIndex = HomeNoteIndex()

    /**
     * UX-18: the filter runs on [Dispatchers.Default], not on the main thread. `stateIn`
     * collects in [viewModelScope], whose dispatcher is `Main.immediate`, so without the
     * `flowOn` every keystroke parsed every note's JSON on the UI thread. [HomeNoteIndex]
     * then keeps a keystroke from redoing work the previous one already did.
     */
    val notes: StateFlow<List<HomeNote>> =
        combine(notesRepository.observeNotes(), searchQuery, _meetingsOnly) { all, query, meetings ->
            searchIndex.filter(all, query, meetings)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /** Selects every note currently in the list (the ones the filter and search leave visible). */
    fun selectAll() {
        if (_selectedIds.value == null) return
        _selectedIds.value = notes.value.mapTo(LinkedHashSet()) { it.id }
    }

    private val _undoableDeletes = MutableSharedFlow<UndoableDelete>(extraBufferCapacity = 1)

    /** Emits after each delete so the screen can offer Undo (B3: no confirm dialog, delete is soft). */
    val undoableDeletes: SharedFlow<UndoableDelete> = _undoableDeletes

    /** Soft-delete every selected note at once (each recoverable via Recently deleted for 1 day). */
    fun deleteSelected() {
        val ids = _selectedIds.value.orEmpty().toList()
        _selectedIds.value = null
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var fileFailures = 0
            ids.forEach { if (!notesRepository.delete(it).filesDeleted) fileFailures++ }
            _undoableDeletes.tryEmit(UndoableDelete(ids, fileFailures))
        }
    }

    /** Undo for [deleteSelected]: each note comes back and is re-exported fresh. */
    fun restore(ids: List<Long>) {
        viewModelScope.launch { ids.forEach { notesRepository.restore(it) } }
    }

    // ── Export health (B8) ──────────────────────────────────────────────────

    /**
     * Notes with no exported file behind them. Zero while no export location is set, since with
     * nothing configured nothing is expected to be exported (same rule as the Settings row).
     */
    val unexportedCount: StateFlow<Int> =
        combine(notesRepository.observeUnexportedCount(), settingsRepository.exportLocationUri) { count, location ->
            if (location == null) 0 else count
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _repairingExports = MutableStateFlow(false)
    val repairingExports: StateFlow<Boolean> = _repairingExports.asStateFlow()

    /** Retry every note that has no exported file and say honestly how it went. */
    fun exportMissing() {
        if (_repairingExports.value) return
        viewModelScope.launch {
            _repairingExports.value = true
            try {
                _snackbarMessage.tryEmit(notesRepository.exportMissing().summary())
            } finally {
                _repairingExports.value = false
            }
        }
    }

    /** Combined Markdown of the selected notes, in list (newest-first) order, for sharing. */
    suspend fun selectedMarkdown(format: ExportFormat): String {
        val ids = _selectedIds.value.orEmpty()
        return notes.first().filter { it.id in ids }
            .joinToString("\n\n---\n\n") { it.note.toMarkdown(format = format) }
    }

    private val _upcoming = MutableStateFlow<UpcomingMeeting?>(null)
    val upcoming: StateFlow<UpcomingMeeting?> = _upcoming.asStateFlow()

    private val _calendarGranted = MutableStateFlow(meetingSource.hasPermission())
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    /** Non-null while a capture is recording/merging anywhere in the app — the chip source. */
    val activeCapture: StateFlow<ActiveCaptureUi?> =
        combine(captureSessionManager.hasActiveSession, captureSessionManager.state) { active, state ->
            if (active) ActiveCaptureUi(state.elapsedLabel, state.meetingTitle, state.paused, state.merging) else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while any capture or merge exists; the screen uses it to hide the New note button (one capture at a time). */
    val hasActiveCapture: StateFlow<Boolean> = activeCapture
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Live input level for the recording card (a separate flow so the list never recomposes at 10 Hz). */
    val level: StateFlow<Float> = captureSessionManager.level

    /** Progress of the merge or rebuild that is running, for the Building card; null otherwise. */
    val mergeStatus: StateFlow<MergeStatus?> = captureSessionManager.mergeStatus

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    // ── Crash recovery (REL-09) ─────────────────────────────────────────────

    /** Non-null when a previous capture died with the process and is still on disk. */
    val pendingRecovery: StateFlow<PendingJournal?> = captureSessionManager.pendingRecovery

    /** True while a recovered capture is being merged into a note — can take minutes. */
    val recovering: StateFlow<Boolean> = captureSessionManager.recovering

    /** Emits the note id once [completeRecovered] finishes, so Home can open it. */
    private val _recoveredNoteId = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val recoveredNoteId: SharedFlow<Long> = _recoveredNoteId

    fun continueRecovered() = captureSessionManager.continueRecovered()

    fun completeRecovered() = captureSessionManager.completeRecovered { id ->
        if (id > 0) {
            _recoveredNoteId.tryEmit(id)
        } else if (id == CaptureSessionManager.MERGE_FAILED) {
            // REL-24: a merge that threw keeps the journal, so say it is safe and can be retried.
            _snackbarMessage.tryEmit("Couldn't build the note this time. Your recording is still saved. Try again.")
        } else {
            _snackbarMessage.tryEmit("That capture had nothing in it — nothing was saved")
        }
    }

    fun discardRecovered() = captureSessionManager.discardRecovered()

    fun dismissRecovery() = captureSessionManager.dismissRecovery()

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
}
