package com.trailmix.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * REL-06: the Recently deleted screen — soft-deleted notes still inside the 1-day
 * recovery window, newest deletion first. Restore re-exports the note fresh;
 * "Delete now" is the immediate unrecoverable path (same as the purge).
 */
@HiltViewModel
class RecentlyDeletedViewModel @Inject constructor(
    private val notesRepository: NotesRepository,
) : ViewModel() {

    val deletedNotes: StateFlow<List<NoteEntity>> = notesRepository.observeDeletedNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage

    init {
        // Same opportunistic purge Home runs — anything past the window disappears
        // before the user ever sees a "Removing soon" row they can't act on.
        viewModelScope.launch { notesRepository.purgeExpiredDeleted() }
    }

    fun restore(id: Long) {
        viewModelScope.launch {
            notesRepository.restore(id)
            _snackbarMessage.tryEmit("Note restored")
        }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch {
            notesRepository.deleteForever(id)
            _snackbarMessage.tryEmit("Note permanently deleted")
        }
    }
}
