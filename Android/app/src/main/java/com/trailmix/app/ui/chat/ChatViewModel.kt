package com.trailmix.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.ai.AiAvailability
import com.trailmix.app.data.ai.DEFAULT_RECIPES
import com.trailmix.app.data.ai.OnDeviceAiProcessor
import com.trailmix.app.data.ai.Recipe
import com.trailmix.app.data.db.ChatMessageEntity
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private var inFlight: Job? = null

    /** Null until the first check finishes; false shows the "simple version, no AI" banner. */
    private val _aiAvailable = MutableStateFlow<Boolean?>(null)
    val aiAvailable: StateFlow<Boolean?> = _aiAvailable.asStateFlow()

    /** The note's title for the top bar; null until loaded. */
    val noteTitle: StateFlow<String?> = notesRepository.observeNote(noteId)
        .map { it?.title }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Add to note" needs a structured summary to append a section to; flat notes do not offer it. */
    val canAddToNote: StateFlow<Boolean> = notesRepository.observeNote(noteId)
        .map { it?.structuredSummary != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _addedToNote = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addedToNote: SharedFlow<Unit> = _addedToNote

    init {
        viewModelScope.launch { _aiAvailable.value = aiProcessor.checkAvailability() is AiAvailability.Available }
    }

    /** K4: cancel the reply in flight. The question stays in the history; no answer is stored. */
    fun stop() {
        inFlight?.cancel()
    }

    /** K3: keep an answer by appending it to the note as a "From chat" section of typed points. */
    fun addToNote(reply: String) {
        viewModelScope.launch {
            val note = notesRepository.observeNote(noteId).first() ?: return@launch
            val summary = note.structuredSummary ?: return@launch
            val updated = ChatToNote.append(summary, reply)
            if (updated == summary) return@launch
            notesRepository.saveStructuredEdits(noteId, note.title, updated)
            _addedToNote.tryEmit(Unit)
        }
    }

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
        inFlight = viewModelScope.launch {
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
        inFlight = viewModelScope.launch {
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
