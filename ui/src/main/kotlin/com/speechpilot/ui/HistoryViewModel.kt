package com.speechpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.data.RoomSessionRepository
import com.speechpilot.data.SessionRecord
import com.speechpilot.data.SpeechPilotDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RoomSessionRepository(
        SpeechPilotDatabase.getInstance(getApplication()).sessionDao()
    )

    val sessions: StateFlow<List<SessionRecord>> = repository.allSessions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteById(sessionId)
        }
    }
}
