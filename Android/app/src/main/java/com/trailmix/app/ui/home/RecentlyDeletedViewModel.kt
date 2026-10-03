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

/** What just happened, for the screen to word. [count] is how many notes it covered. */
data class DeletedEvent(val kind: Kind, val count: Int = 1) {
    enum class Kind { RESTORED, ERASED }
}

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

    private val _events = MutableSharedFlow<DeletedEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<DeletedEvent> = _events

    init {
        // Same opportunistic purge Home runs — anything past the window disappears
        // before the user ever sees a "Removing soon" row they can't act on.
        viewModelScope.launch { notesRepository.purgeExpiredDeleted() }
    }

    fun restore(id: Long) {
        viewModelScope.launch {
            notesRepository.restore(id)
            _events.tryEmit(DeletedEvent(DeletedEvent.Kind.RESTORED))
        }
    }

    fun deleteForever(id: Long) {
        viewModelScope.launch {
            notesRepository.deleteForever(id)
            _events.tryEmit(DeletedEvent(DeletedEvent.Kind.ERASED))
        }
    }

    /** "Delete all now": erases everything currently in the list, after the screen's own confirm. */
    fun deleteAllForever() {
        val ids = deletedNotes.value.map { it.id }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { notesRepository.deleteForever(it) }
            _events.tryEmit(DeletedEvent(DeletedEvent.Kind.ERASED, ids.size))
        }
    }
}
