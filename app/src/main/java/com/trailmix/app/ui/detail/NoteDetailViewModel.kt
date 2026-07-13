package com.trailmix.app.ui.detail

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import com.trailmix.app.data.obsidian.ObsidianExporter
import com.trailmix.app.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NoteDetailUiState(
    val note: NoteEntity? = null,
    val metaLine: String = "",
    val vaultName: String? = null,
)

@HiltViewModel
class NoteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    notesRepository: NotesRepository,
    settingsRepository: SettingsRepository,
    private val obsidianExporter: ObsidianExporter,
) : ViewModel() {
    private val noteId: Long = checkNotNull(savedStateHandle["noteId"])

    val uiState: StateFlow<NoteDetailUiState> = combine(
        notesRepository.observeNote(noteId),
        settingsRepository.vaultName,
    ) { note, vaultName ->
        NoteDetailUiState(
            note = note,
            vaultName = vaultName,
            metaLine = note?.let {
                val time = SimpleDateFormat("MMM d · h:mm a", Locale.getDefault())
                    .format(Date(it.createdAtEpochMs))
                val duration = formatDuration(it.durationMs)
                "$time · $duration"
            }.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteDetailUiState())

    fun openInObsidian(): Intent? {
        val note = uiState.value.note ?: return null
        val path = note.obsidianRelativePath ?: return null
        return obsidianExporter.openInObsidian(path, uiState.value.vaultName)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }
}
