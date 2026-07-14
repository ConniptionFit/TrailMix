package com.trailmix.app.ui.note

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
) : ViewModel() {

    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    val note: StateFlow<NoteEntity?> = notesRepository.observeNote(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            notesRepository.delete(noteId)
            onDeleted()
        }
    }
}
