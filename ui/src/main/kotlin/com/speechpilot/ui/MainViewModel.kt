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
import com.speechpilot.transcription.AndroidSpeechRecognizerTranscriber
import com.speechpilot.transcription.NoOpLocalTranscriber
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettings = DataStoreAppSettings(getApplication())
    private val repository = RoomSessionRepository(
        SpeechPilotDatabase.getInstance(getApplication()).sessionDao()
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var latestPreferences = UserPreferences()
    private var sessionManager: SpeechCoachSessionManager? = null
    private var liveStateJob: Job? = null

    init {
        viewModelScope.launch {
            appSettings.preferences.collect { prefs ->
                latestPreferences = prefs
                val isSessionActive = sessionManager?.liveState?.value?.sessionState == SessionState.Active
                if (!isSessionActive) {
                    recreateSessionManager(prefs)
                }
            }
        }
    }

    private fun recreateSessionManager(prefs: UserPreferences) {
        liveStateJob?.cancel()
        sessionManager?.release()

        val transcriber = if (prefs.localTranscriptDebugEnabled) {
            AndroidSpeechRecognizerTranscriber(getApplication())
        } else {
            NoOpLocalTranscriber()
        }

        val mgr = SpeechCoachSessionManager.create(
            feedbackDispatcher = VibrationFeedbackDispatcher(getApplication()),
            sessionRepository = repository,
            feedbackDecision = ThresholdFeedbackDecision(
                targetWpm = prefs.targetWpm.toDouble(),
                tolerancePct = prefs.tolerancePct.toDouble(),
                cooldownMs = prefs.feedbackCooldownMs
            ),
            localTranscriber = transcriber
        )
        sessionManager = mgr

        liveStateJob = viewModelScope.launch {
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
                        transcriptText = live.transcriptText,
                        transcriptRollingWpm = live.transcriptRollingWpm,
                        segmentCount = live.stats.segmentCount,
                        latestFeedback = live.latestFeedback,
                        alertActive = live.alertActive,
                        sessionMode = live.mode,
                        errorMessage = errorMessage,
                        debugInfo = live.debugInfo,
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
        if (!_uiState.value.permissionGranted) {
            _uiState.update { it.copy(errorMessage = "Microphone permission is required to start a session.") }
            return
        }

        if (sessionManager == null || sessionManager?.state?.value == SessionState.Idle) {
            recreateSessionManager(latestPreferences)
        }

        _uiState.update { it.copy(errorMessage = null) }
        val mgr = sessionManager ?: return
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
        liveStateJob?.cancel()
        sessionManager?.release()
    }
}
