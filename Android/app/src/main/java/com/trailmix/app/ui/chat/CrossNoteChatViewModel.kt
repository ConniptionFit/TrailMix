package com.trailmix.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.CrossNoteRetrieval
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.db.ConversationKey
import com.trailmix.app.data.db.ConversationMessageEntity
import com.trailmix.app.data.db.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cross-note chat (AI-10). Mirrors [ChatViewModel]'s shape — a single nav-arg-derived identity,
 * a Flow of persisted messages, a `send` that posts the user turn then the assistant reply —
 * generalized to a note *set* instead of one `noteId`: see [ConversationKey] for why that set,
 * not a separately allocated id, is the conversation's identity.
 */
@HiltViewModel
class CrossNoteChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    private val aiProcessor: OnDeviceAiProcessor,
) : ViewModel() {

    private val noteIds: List<Long> =
        checkNotNull(savedStateHandle.get<String>("noteIds")).split(",").mapNotNull { it.toLongOrNull() }
    private val conversationKey = ConversationKey.of(noteIds)

    val messages: StateFlow<List<ConversationMessageEntity>> =
        notesRepository.observeConversation(conversationKey)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Loaded once for the header ("Chat — 3 notes") — the conversation itself re-fetches the
     *  live notes on every [send] so a mid-conversation edit is always reflected. */
    private val _noteTitles = MutableStateFlow<List<String>>(emptyList())
    val noteTitles: StateFlow<List<String>> = _noteTitles.asStateFlow()

    init {
        viewModelScope.launch { _noteTitles.value = notesRepository.getNotesByIds(noteIds).map { it.title } }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                notesRepository.addConversationMessage(conversationKey, "user", trimmed)
                val notes = notesRepository.getNotesByIds(noteIds)
                if (notes.isEmpty()) return@launch
                val context = CrossNoteRetrieval.buildContext(trimmed, notes, CROSS_NOTE_CONTEXT_CHARS)
                val history = messages.value.map { it.role to it.text }
                val reply = aiProcessor.chatAcrossNotes(
                    context = context,
                    noteTitles = notes.map { it.title },
                    history = history,
                    userMessage = trimmed,
                )
                notesRepository.addConversationMessage(conversationKey, "assistant", reply)
            } finally {
                _busy.value = false
            }
        }
    }

    private companion object {
        const val CROSS_NOTE_CONTEXT_CHARS = 8_000
    }
}
