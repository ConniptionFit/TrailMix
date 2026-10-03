package com.trailmix.app.ui.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.RegeneratePolicy
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.export.ExportFormat
import com.trailmix.app.data.media.MatchedPhoto
import com.trailmix.app.data.media.PhotoSource
import com.trailmix.app.data.model.StructuredSummary
import com.trailmix.app.data.model.TemplateOption
import com.trailmix.app.data.model.TemplateOptions
import com.trailmix.app.data.settings.SettingsRepository
import com.trailmix.app.data.speech.CaptureSessionManager
import com.trailmix.app.data.speech.MergeStatus
import com.trailmix.app.ui.home.ActiveCaptureUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    private val captureSessionManager: CaptureSessionManager,
    settingsRepository: SettingsRepository,
    private val photoSource: PhotoSource,
) : ViewModel() {

    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    val note: StateFlow<NoteEntity?> = notesRepository.observeNote(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Persisted default (Settings) the share sheet's one-off picker starts from. */
    val exportFormat: StateFlow<ExportFormat> = settingsRepository.exportFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExportFormat.LLM_OPTIMIZED)

    // ── UX-39/UX-40: regenerate / re-enhance ────────────────────────────────

    /** Built-ins plus the user's custom templates — same list the Capture chips show. */
    val templateOptions: StateFlow<List<TemplateOption>> = settingsRepository.customSummaryTemplates
        .map { TemplateOptions.all(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TemplateOptions.builtIns())

    private val _regenerating = MutableStateFlow(false)

    /** True while a regeneration started from this screen is running (drives the disabled state). */
    val regenerating: StateFlow<Boolean> = _regenerating.asStateFlow()

    /** Live input level for the in-progress card (kept out of any screen-wide state). */
    val level: StateFlow<Float> = captureSessionManager.level

    /** Progress of the rebuild that is running (N6); null when nothing is merging. */
    val mergeStatus: StateFlow<MergeStatus?> = captureSessionManager.mergeStatus

    private val _rebuildTemplate = MutableStateFlow<String?>(null)

    /** Stored template value the running rebuild uses, for the "Rebuilding as …" title. */
    val rebuildTemplate: StateFlow<String?> = _rebuildTemplate.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One-shot snackbar lines (refusal / failure). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * Re-run the merge for this note with [template] (null = keep the note's own). Runs behind
     * the capture foreground service — see [CaptureSessionManager.regenerateNote]. A refusal or
     * failure leaves the note unchanged and surfaces a snackbar line.
     */
    fun regenerate(template: String?) {
        val current = note.value ?: return
        if (_regenerating.value) return
        val s = captureSessionManager.state.value
        val refusal = RegeneratePolicy.refusal(
            recording = s.recording,
            paused = s.paused,
            merging = s.merging,
            recovering = false,
            typedFragments = current.typedFragments,
            transcript = current.transcript,
        )
        if (refusal != null) {
            _messages.tryEmit(RegeneratePolicy.failureMessage(refusal))
            return
        }
        _regenerating.value = true
        _rebuildTemplate.value = template ?: current.template
        captureSessionManager.regenerateNote(noteId, template) { ok ->
            _regenerating.value = false
            _rebuildTemplate.value = null
            if (!ok) _messages.tryEmit(RegeneratePolicy.failureMessage(null))
        }
    }

    /** Stops the rebuild started from this screen; the note stays as it was. */
    fun cancelRebuild() = captureSessionManager.cancelRebuild()

    /** UX-40: persist hand-edited raw notes — only the `typedFragments` column. */
    fun saveRawNotes(text: String, onDone: () -> Unit) {
        viewModelScope.launch {
            notesRepository.updateTypedFragments(noteId, text)
            onDone()
        }
    }

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
            if (active) ActiveCaptureUi(state.elapsedLabel, state.meetingTitle, state.paused, state.merging) else null
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

    /** Lets the screen put a one-off line (for example an export result) on the snackbar. */
    fun postMessage(text: String) {
        _messages.tryEmit(text)
    }

    /** N1: tick or untick a Next Step; the step stays where it is. */
    fun setActionDone(index: Int, done: Boolean) {
        viewModelScope.launch { notesRepository.setActionDone(noteId, index, done) }
    }

    /** N5: save the structure editor's result (title plus the edited summary). */
    fun saveStructuredEdits(title: String, summary: StructuredSummary, onDone: () -> Unit) {
        viewModelScope.launch {
            notesRepository.saveStructuredEdits(noteId, title, summary)
            onDone()
        }
    }

    /** Overflow "Export now": reports whether the note reached the export folder. */
    fun exportNow(onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(notesRepository.exportNow(noteId)) }
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
