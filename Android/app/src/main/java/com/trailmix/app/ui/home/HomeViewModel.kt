package com.trailmix.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.export.ExportTarget
import com.trailmix.app.data.settings.SettingsRepository
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

/** Which export targets are currently configured — drives the long-press "Move" menu (CAP-05). */
data class ExportTargetsConfigured(val obsidian: Boolean, val drive: Boolean) {
    val any: Boolean get() = obsidian || drive
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
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

    val exportTargetsConfigured: StateFlow<ExportTargetsConfigured> =
        combine(settingsRepository.vaultUri, settingsRepository.driveUri) { vault, drive ->
            ExportTargetsConfigured(obsidian = vault != null, drive = drive != null)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportTargetsConfigured(false, false))

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    init {
        refreshUpcoming()
    }

    fun refreshUpcoming() {
        _calendarGranted.value = meetingSource.hasPermission()
        viewModelScope.launch { _upcoming.value = meetingSource.nextMeeting() }
    }

    /** Delete + cascade to any tracked export files (CAP-05). Fail-soft: a downstream file
     * delete failure never blocks the local delete, just surfaces a Snackbar hint. */
    fun deleteNote(id: Long) {
        viewModelScope.launch {
            val result = notesRepository.delete(id)
            if (!result.filesDeleted) {
                _snackbarMessage.tryEmit("Note deleted locally; couldn't remove the synced copy")
            }
        }
    }

    /** "Move" (CAP-05): re-export [id] to a freshly SAF-picked folder for [target]. */
    fun moveExport(id: Long, target: ExportTarget, treeUri: Uri) {
        viewModelScope.launch {
            val ok = notesRepository.moveExport(id, target, treeUri)
            _snackbarMessage.tryEmit(
                if (ok) "Moved to the new folder" else "Couldn't export to that folder",
            )
        }
    }
}
