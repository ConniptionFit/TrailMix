package com.trailmix.app.ui.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.media.MatchedPhoto
import com.trailmix.app.data.media.PhotoSource
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.ui.home.ActiveCaptureUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    captureSessionManager: CaptureSessionManager,
    settingsRepository: SettingsRepository,
    private val photoSource: PhotoSource,
) : ViewModel() {

    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    val note: StateFlow<NoteEntity?> = notesRepository.observeNote(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Persisted default (Settings) the share sheet's one-off picker starts from. */
    val exportFormat: StateFlow<ExportFormat> = settingsRepository.exportFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportFormat.LLM_OPTIMIZED)

    // ── Photo-export feature ────────────────────────────────────────────────

    private val _photoPermissionGranted = MutableStateFlow(photoSource.hasPermission())
    val photoPermissionGranted: StateFlow<Boolean> = _photoPermissionGranted.asStateFlow()

    private val _matchedPhotos = MutableStateFlow<List<MatchedPhoto>>(emptyList())
    val matchedPhotos: StateFlow<List<MatchedPhoto>> = _matchedPhotos.asStateFlow()

    /** Re-checks the permission (called after returning from the system prompt) and, once
     * granted, loads photos from this note's session window. */
    fun refreshPhotoPermission() {
        _photoPermissionGranted.value = photoSource.hasPermission()
        if (_photoPermissionGranted.value) loadMatchedPhotos()
    }

    fun loadMatchedPhotos() {
        val current = note.value ?: return
        viewModelScope.launch {
            _matchedPhotos.value = photoSource.photosBetween(
                startEpochMs = current.createdAtEpochMs,
                endEpochMs = current.createdAtEpochMs + current.durationMs,
            )
        }
    }

    /** Persists the selection and re-exports so it's reflected on disk immediately. */
    fun setSelectedPhotos(photoUris: List<String>) {
        viewModelScope.launch {
            notesRepository.setSelectedPhotos(noteId, photoUris)
        }
    }

    /**
     * Non-null while a capture is recording/merging *anywhere* in the app — same source as
     * the Home chip (CAP-10) — so the top bar here can surface a way back into a live
     * capture even when the user has navigated into an unrelated note's detail screen
     * (Part 3.2 verification: navigating Home → a different note while recording must not
     * interrupt the session, and the in-progress affordance should stay reachable).
     */
    val activeCapture: StateFlow<ActiveCaptureUi?> =
        combine(captureSessionManager.hasActiveSession, captureSessionManager.state) { active, state ->
            if (active) ActiveCaptureUi(state.elapsedLabel, state.meetingTitle, state.paused) else null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleShowSources() {
        val current = note.value ?: return
        viewModelScope.launch {
            notesRepository.setShowSources(noteId, !current.showSources)
        }
    }

    /** Persist a hand-edited title/body (UX-01). */
    fun saveEdits(title: String, body: String, onDone: () -> Unit) {
        viewModelScope.launch {
            notesRepository.updateNoteContent(noteId, title.trim(), body)
            onDone()
        }
    }

    /** UX-22: correct one transcript line in place, re-exporting so the file on disk picks it up. */
    fun updateTranscriptLine(lineIndex: Int, newText: String, onDone: () -> Unit) {
        viewModelScope.launch {
            notesRepository.updateTranscriptLine(noteId, lineIndex, newText)
            onDone()
        }
    }

    /** UX-04: move a structured-summary topic section up/down; persists + re-exports. */
    fun moveSummarySection(from: Int, to: Int) {
        viewModelScope.launch {
            notesRepository.moveSummarySection(noteId, from, to)
        }
    }

    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            notesRepository.delete(noteId)
            onDeleted()
        }
    }
}
