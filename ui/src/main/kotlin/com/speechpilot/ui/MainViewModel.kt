package com.speechpilot.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.session.SessionState
import com.speechpilot.session.SpeechCoachSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val sessionManager = SpeechCoachSessionManager()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.liveState.collect { live ->
                _uiState.update { current ->
                    current.copy(
                        isSessionActive = live.sessionState == SessionState.Active,
                        isListening = live.isListening,
                        isSpeechDetected = live.isSpeechDetected,
                        currentWpm = live.currentWpm,
                        smoothedWpm = live.smoothedWpm,
                        segmentCount = live.stats.segmentCount,
                        latestFeedback = live.latestFeedback,
                        statusText = when (live.sessionState) {
                            SessionState.Idle ->
                                if (current.permissionGranted) "Ready" else "Microphone permission required"
                            SessionState.Starting -> "Starting…"
                            SessionState.Active -> "Listening"
                            SessionState.Stopping -> "Stopping…"
                            is SessionState.Error -> "Error"
                        }
                    )
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { current ->
            current.copy(
                permissionGranted = granted,
                statusText = if (granted) "Ready" else "Microphone permission required"
            )
        }
    }

    fun startSession() {
        if (!_uiState.value.permissionGranted) return
        viewModelScope.launch { sessionManager.start() }
    }

    fun stopSession() {
        viewModelScope.launch { sessionManager.stop() }
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager.release()
    }
}
