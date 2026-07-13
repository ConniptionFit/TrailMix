package com.trailmix.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trailmix.app.data.calendar.UpcomingMeeting
import com.trailmix.app.data.calendar.UpcomingMeetingSource
import com.trailmix.app.data.db.NoteEntity
import com.trailmix.app.data.db.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    notesRepository: NotesRepository,
    private val meetingSource: UpcomingMeetingSource,
) : ViewModel() {

    val notes: StateFlow<List<NoteEntity>> = notesRepository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _upcoming = MutableStateFlow<UpcomingMeeting?>(null)
    val upcoming: StateFlow<UpcomingMeeting?> = _upcoming.asStateFlow()

    private val _calendarGranted = MutableStateFlow(meetingSource.hasPermission())
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    init {
        refreshUpcoming()
    }

    fun refreshUpcoming() {
        _calendarGranted.value = meetingSource.hasPermission()
        viewModelScope.launch { _upcoming.value = meetingSource.nextMeeting() }
    }
}
