package com.trailmix.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Home in-progress-transcription chip needs to render (CAP-10). */
data class ActiveCaptureUi(val elapsedLabel: String, val meetingTitle: String?)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val meetingSource: UpcomingMeetingSource,
    private val captureSessionManager: CaptureSessionManager,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> = notesRepository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _upcoming = MutableStateFlow<UpcomingMeeting?>(null)
    val upcoming: StateFlow<UpcomingMeeting?> = _upcoming.asStateFlow()

    private val _calendarGranted = MutableStateFlow(meetingSource.hasPermission())
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    /** Non-null while a capture is recording/merging anywhere in the app — the chip source. */
    val activeCapture: StateFlow<ActiveCaptureUi?> =
        combine(captureSessionManager.hasActiveSession, captureSessionManager.state) { active, state ->
            if (active) ActiveCaptureUi(state.elapsedLabel, state.meetingTitle) else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    init {
        refreshUpcoming()
    }

    fun refreshUpcoming() {
        _calendarGranted.value = meetingSource.hasPermission()
        viewModelScope.launch { _upcoming.value = meetingSource.nextMeeting() }
    }

    /** Delete + cascade to the tracked export file (CAP-05). Fail-soft: a downstream file
     * delete failure never blocks the local delete, just surfaces a Snackbar hint. */
    fun deleteNote(id: Long) {
        viewModelScope.launch {
            val result = notesRepository.delete(id)
            if (!result.filesDeleted) {
                _snackbarMessage.tryEmit("Note deleted locally; couldn't remove the exported copy")
            }
        }
    }
}
