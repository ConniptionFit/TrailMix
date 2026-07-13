package com.trailmix.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.DEFAULT_RECIPES
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.db.ChatMessageEntity
import com.trailmix.app.data.db.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    private val aiProcessor: OnDeviceAiProcessor,
) : ViewModel() {

    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    val recipes: List<Recipe> = DEFAULT_RECIPES

    val messages: StateFlow<List<ChatMessageEntity>> = notesRepository.observeChat(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                notesRepository.addChatMessage(noteId, "user", trimmed)
                val note = notesRepository.observeNote(noteId).first() ?: return@launch
                val history = messages.value.map { it.role to it.text }
                val reply = aiProcessor.chat(
                    noteBody = note.segments.joinToString(" ") { it.text },
                    transcript = note.transcript.joinToString("\n") { it.text },
                    history = history,
                    userMessage = trimmed,
                )
                notesRepository.addChatMessage(noteId, "assistant", reply)
            } finally {
                _busy.value = false
            }
        }
    }

    fun runRecipe(recipe: Recipe) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                notesRepository.addChatMessage(noteId, "user", recipe.displayMessage())
                val note = notesRepository.observeNote(noteId).first() ?: return@launch
                val reply = aiProcessor.chat(
                    noteBody = note.segments.joinToString(" ") { it.text },
                    transcript = note.transcript.joinToString("\n") { it.text },
                    history = emptyList(),
                    userMessage = recipe.prompt,
                )
                notesRepository.addChatMessage(noteId, "assistant", reply)
            } finally {
                _busy.value = false
            }
        }
    }
}

private fun Recipe.displayMessage(): String = when (name) {
    "Follow-up email" -> "Draft the follow-up email"
    "Create ticket" -> "Create a ticket from this note"
    "Summarize" -> "Summarize this note"
    "Action items" -> "List the action items"
    else -> name
}
