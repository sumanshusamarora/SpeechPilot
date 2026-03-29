package com.speechpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.data.RoomSessionRepository
import com.speechpilot.data.SpeechPilotDatabase
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.feedback.VibrationFeedbackDispatcher
import com.speechpilot.session.SessionMode
import com.speechpilot.session.SessionState
import com.speechpilot.session.SpeechCoachSessionManager
import com.speechpilot.settings.DataStoreAppSettings
import com.speechpilot.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettings = DataStoreAppSettings(getApplication())
    private val repository = RoomSessionRepository(
        SpeechPilotDatabase.getInstance(getApplication()).sessionDao()
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Session manager is initialised once the first settings emission arrives.
    // DataStore emits immediately from its on-disk cache, so this completes before
    // a user can realistically tap "Start".
    // All access to sessionManager is on the main thread (viewModelScope + UI calls),
    // so no synchronization is needed beyond null-safety checks.
    private var sessionManager: SpeechCoachSessionManager? = null

    init {
        viewModelScope.launch {
            // Read initial stored preferences (or defaults if nothing saved yet).
            val prefs: UserPreferences = appSettings.preferences.first()
            val mgr = SpeechCoachSessionManager(
                feedbackDispatcher = VibrationFeedbackDispatcher(getApplication()),
                sessionRepository = repository,
                feedbackDecision = ThresholdFeedbackDecision(
                    targetWpm = prefs.targetWpm.toDouble(),
                    tolerancePct = prefs.tolerancePct.toDouble(),
                    cooldownMs = prefs.feedbackCooldownMs
                )
            )
            sessionManager = mgr
            mgr.liveState.collect { live ->
                _uiState.update { current ->
                    val isActive = live.sessionState == SessionState.Active
                    val errorMessage = when (val s = live.sessionState) {
                        is SessionState.Error -> s.cause.localizedMessage ?: "Session error"
                        is SessionState.Idle -> null
                        else -> current.errorMessage
                    }
                    current.copy(
                        isSessionActive = isActive,
                        isListening = live.isListening,
                        isSpeechDetected = live.isSpeechDetected,
                        currentWpm = live.currentWpm,
                        smoothedWpm = live.smoothedWpm,
                        segmentCount = live.stats.segmentCount,
                        latestFeedback = live.latestFeedback,
                        alertActive = live.alertActive,
                        sessionMode = live.mode,
                        errorMessage = errorMessage,
                        statusText = when (live.sessionState) {
                            SessionState.Idle ->
                                if (current.permissionGranted) "Ready" else "Microphone permission required"
                            SessionState.Starting -> "Starting…"
                            SessionState.Active ->
                                if (live.isSpeechDetected) "Speech detected" else "Listening…"
                            SessionState.Stopping -> "Stopping…"
                            is SessionState.Error -> "Session error"
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

    fun startSession(mode: SessionMode = SessionMode.Active) {
        val mgr = sessionManager ?: return
        if (!_uiState.value.permissionGranted) {
            _uiState.update { it.copy(errorMessage = "Microphone permission is required to start a session.") }
            return
        }
        // Clear any previous error before starting.
        _uiState.update { it.copy(errorMessage = null) }
        viewModelScope.launch { mgr.start(mode) }
    }

    fun stopSession() {
        val mgr = sessionManager ?: return
        viewModelScope.launch { mgr.stop() }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager?.release()
    }
}
