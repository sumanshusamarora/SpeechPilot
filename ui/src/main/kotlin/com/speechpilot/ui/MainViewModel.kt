package com.speechpilot.ui

import android.app.Application
import android.net.Uri
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
import com.speechpilot.transcription.RoutingLocalTranscriber
import com.speechpilot.transcription.VoskLocalTranscriber
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
                _uiState.update { it.copy(transcriptionEnabled = prefs.transcriptionEnabled) }
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

        val transcriber = if (prefs.transcriptionEnabled) {
            RoutingLocalTranscriber(
                primaryTranscriber = VoskLocalTranscriber.create(getApplication()),
                fallbackTranscriber = AndroidSpeechRecognizerTranscriber(getApplication())
            )
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
            localTranscriber = transcriber,
            transcriptDebugEnabled = prefs.transcriptionEnabled
        )
        sessionManager = mgr
        startWatchingLiveState(mgr, isFileSession = false, fileSessionUri = null)
    }

    private fun recreateFileSessionManager(prefs: UserPreferences, uri: Uri) {
        liveStateJob?.cancel()
        sessionManager?.release()

        val transcriber = if (prefs.transcriptionEnabled) {
            RoutingLocalTranscriber(
                primaryTranscriber = VoskLocalTranscriber.create(getApplication()),
                fallbackTranscriber = AndroidSpeechRecognizerTranscriber(getApplication())
            )
        } else {
            NoOpLocalTranscriber()
        }

        val mgr = SpeechCoachSessionManager.createForFile(
            context = getApplication(),
            audioFileUri = uri,
            feedbackDispatcher = VibrationFeedbackDispatcher(getApplication()),
            sessionRepository = repository,
            feedbackDecision = ThresholdFeedbackDecision(
                targetWpm = prefs.targetWpm.toDouble(),
                tolerancePct = prefs.tolerancePct.toDouble(),
                cooldownMs = prefs.feedbackCooldownMs
            ),
            localTranscriber = transcriber,
            transcriptDebugEnabled = prefs.transcriptionEnabled
        )
        sessionManager = mgr
        startWatchingLiveState(mgr, isFileSession = true, fileSessionUri = uri.toString())
    }

    /**
     * Subscribes to [mgr]'s live state and maps it to [MainUiState].
     *
     * [isFileSession] controls the status text wording and is stored in [MainUiState] so the
     * UI can show the appropriate controls.
     */
    private fun startWatchingLiveState(
        mgr: SpeechCoachSessionManager,
        isFileSession: Boolean,
        fileSessionUri: String?
    ) {
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
                        isSpeechActive = live.isSpeechActive,
                        isSpeechDetected = live.isSpeechDetected,
                        micLevel = live.micLevel,
                        currentWpm = live.currentWpm,
                        smoothedWpm = live.smoothedWpm,
                        transcriptDebug = live.transcriptDebug,
                        segmentCount = live.stats.segmentCount,
                        latestFeedback = live.latestFeedback,
                        alertActive = live.alertActive,
                        sessionMode = live.mode,
                        errorMessage = errorMessage,
                        debugInfo = live.debugInfo,
                        isFileSession = isFileSession,
                        fileSessionUri = fileSessionUri,
                        statusText = when (live.sessionState) {
                            SessionState.Idle ->
                                if (current.permissionGranted) "Ready" else "Microphone permission required"
                            SessionState.Starting -> "Starting…"
                            SessionState.Active ->
                                if (isFileSession) "Analyzing file…"
                                else if (live.isSpeechActive) "Speaking…" else "Listening…"
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

    /**
     * Starts a session that analyses an uploaded audio file instead of the live microphone.
     *
     * The [uri] must be readable by the content resolver. For documents selected via
     * [android.content.Intent.ACTION_OPEN_DOCUMENT], take a persistable URI permission before
     * calling this so that re-analysis from history works after an app restart.
     *
     * Does not require the `RECORD_AUDIO` permission.
     */
    fun startFileSession(uri: Uri) {
        _uiState.update { it.copy(errorMessage = null) }
        recreateFileSessionManager(latestPreferences, uri)
        val mgr = sessionManager ?: return
        viewModelScope.launch { mgr.start() }
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
