package com.trailmix.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.DEFAULT_RECIPES
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.db.ChatMessageEntity
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository,
    private val aiProcessor: OnDeviceAiProcessor,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    /** Built-ins first, then any user-defined recipes (UX-06) — one chip row, same execution path. */
    val recipes: StateFlow<List<Recipe>> = settingsRepository.customRecipes
        .map { DEFAULT_RECIPES + it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_RECIPES)

    val messages: StateFlow<List<ChatMessageEntity>> = notesRepository.observeChat(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * AI-14 (2026-09-19): [com.trailmix.app.data.db.NoteEntity.segments]/`.transcript` are
     * getters that re-decode their JSON columns on every access — cheap for a short note, real
     * work for a keynote-scale transcript. Both `send()` and `runRecipe()` previously built
     * `noteBody`/`transcript` inline on `viewModelScope` (`Main.immediate`), so every message
     * sent decoded the whole transcript on the UI thread before the AI call (which does its
     * own `withContext(Dispatchers.Default)` internally) ever started. Moved here, off-main.
     */
    private suspend fun promptContextFor(note: NoteEntity): Pair<String, String> =
        withContext(Dispatchers.Default) {
            note.segments.joinToString(" ") { it.text } to note.transcript.joinToString("\n") { it.text }
        }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                notesRepository.addChatMessage(noteId, "user", trimmed)
                val note = notesRepository.observeNote(noteId).first() ?: return@launch
                val history = messages.value.map { it.role to it.text }
                val (noteBody, transcript) = promptContextFor(note)
                val reply = aiProcessor.chat(
                    noteBody = noteBody,
                    transcript = transcript,
                    history = history,
                    userMessage = trimmed,
                    attendees = note.attendees,
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
                val (noteBody, transcript) = promptContextFor(note)
                val reply = aiProcessor.chat(
                    noteBody = noteBody,
                    transcript = transcript,
                    history = emptyList(),
                    userMessage = recipe.prompt,
                    attendees = note.attendees,
                )
                // Tagged with the recipe name (OBS-01) so it's exported with the note —
                // custom recipes (UX-06) get the identical treatment.
                notesRepository.addChatMessage(noteId, "assistant", reply, recipeName = recipe.name)
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
    else -> "Run \"$name\""
}
