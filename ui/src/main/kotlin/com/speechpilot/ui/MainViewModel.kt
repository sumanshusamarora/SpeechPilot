package com.speechpilot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.data.RoomSessionRepository
import com.speechpilot.data.SpeechPilotDatabase
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.feedback.VibrationFeedbackDispatcher
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
                    current.copy(
                        isSessionActive = live.sessionState == SessionState.Active,
                        isListening = live.isListening,
                        isSpeechDetected = live.isSpeechDetected,
                        currentWpm = live.currentWpm,
                        smoothedWpm = live.smoothedWpm,
                        segmentCount = live.stats.segmentCount,
                        latestFeedback = live.latestFeedback,
                        alertActive = live.alertActive,
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
        val mgr = sessionManager ?: return
        if (!_uiState.value.permissionGranted) return
        viewModelScope.launch { mgr.start() }
    }

    fun stopSession() {
        val mgr = sessionManager ?: return
        viewModelScope.launch { mgr.stop() }
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager?.release()
    }
}
