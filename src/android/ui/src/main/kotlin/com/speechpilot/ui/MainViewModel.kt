package com.speechpilot.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.speechpilot.data.RoomSessionRepository
import com.speechpilot.data.SpeechPilotDatabase
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.feedback.VibrationFeedbackDispatcher
import com.speechpilot.modelmanager.KnownModels
import com.speechpilot.modelmanager.LocalModelDescriptor
import com.speechpilot.modelmanager.ModelInstallState
import com.speechpilot.modelmanager.WorkManagerLocalModelManager
import com.speechpilot.session.RealtimeSessionConfig
import com.speechpilot.session.RealtimeSessionManager
import com.speechpilot.session.SessionManager
import com.speechpilot.session.SessionMode
import com.speechpilot.session.SessionState
import com.speechpilot.session.SpeechCoachSessionManager
import com.speechpilot.settings.DataStoreAppSettings
import com.speechpilot.settings.LiveSessionBackend
import com.speechpilot.settings.UserPreferences
import com.speechpilot.transcription.AndroidSpeechRecognizerTranscriber
import com.speechpilot.transcription.NoOpLocalTranscriber
import com.speechpilot.transcription.RoutingLocalTranscriber
import com.speechpilot.transcription.VoskLocalTranscriber
import com.speechpilot.transcription.WhisperBenchmarkConfig
import com.speechpilot.transcription.WhisperBenchmarkRunner
import com.speechpilot.transcription.WhisperChunkingConfig
import com.speechpilot.transcription.WhisperCppLocalTranscriber
import com.speechpilot.transcription.WhisperNative
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appSettings = DataStoreAppSettings(getApplication())
    private val repository = RoomSessionRepository(
        SpeechPilotDatabase.getInstance(getApplication()).sessionDao()
    )

    /**
     * WorkManager-backed model manager.
     *
        * Uses WorkManager so model downloads survive app backgrounding.
     * [WorkManagerLocalModelManager.startObserving] is called in [init] to wire WorkInfo updates
     * into the [StateFlow] that the UI observes.
     */
    private val modelManager = WorkManagerLocalModelManager(getApplication())
        private val whisperBenchmarkRunner = WhisperBenchmarkRunner()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var latestPreferences = UserPreferences()
    private var sessionManager: SessionManager? = null
    private var liveStateJob: Job? = null
    private var modelObserveJob: Job? = null

    init {
        // Start translating WorkInfo updates into StateFlow<ModelInstallState>.
        modelObserveJob = modelManager.startObserving(viewModelScope)

        viewModelScope.launch {
            appSettings.preferences.collect { prefs ->
                latestPreferences = prefs
                _uiState.update {
                    it.copy(
                        transcriptionEnabled = prefs.transcriptionEnabled || useRealtimeBackend(prefs),
                        localTranscriptionEnabled = prefs.transcriptionEnabled,
                        realtimeBackendEnabled = useRealtimeBackend(prefs),
                        whisperSelected = prefs.transcriptionEnabled && prefs.preferWhisperBackend,
                        whisperNativeLibLoaded = WhisperNative.isAvailable,
                    )
                }

                // Provision only the model required for the currently selected backend.
                if (prefs.transcriptionEnabled) {
                    triggerActiveBackendModelProvisioning(prefs)
                }

                val isSessionActive =
                    sessionManager?.liveState?.value?.sessionState == SessionState.Active
                if (!isSessionActive) {
                    recreateSessionManager(prefs)
                }
            }
        }

        // Observe only the active backend's model install state.
        observeActiveModelState()
    }

    // -------------------------------------------------------------------------
    // Model provisioning — active-backend-only
    // -------------------------------------------------------------------------

    /**
     * Triggers provisioning of the model required for the active backend.
     *
     * Only one model is provisioned at a time:
     * - Vosk selected → provision Vosk model
     * - Whisper selected → provision Whisper model
     * - Transcription disabled → no provisioning
     *
     * The [WorkManagerLocalModelManager] deduplicates concurrent calls, so this is safe to
     * invoke on every preference update.
     */
    private fun triggerActiveBackendModelProvisioning(prefs: UserPreferences) {
        viewModelScope.launch {
            val modelId = activeModelDescriptor(prefs).id
            modelManager.ensureInstalled(modelId)
        }
    }

    /**
     * Returns the [LocalModelDescriptor] for the backend selected in [prefs].
     * Used both for provisioning and for surfacing model metadata in the UI.
     */
    private fun activeModelDescriptor(prefs: UserPreferences): LocalModelDescriptor =
        if (prefs.preferWhisperBackend) KnownModels.whisperDescriptor(prefs.whisperModelId)
        else KnownModels.VOSK_SMALL_EN_US

    /**
     * Observes the install state of whichever model is required by the active backend and
     * mirrors it into [MainUiState.activeModelInstallState].
     *
     * Uses `flatMapLatest` so the inner model-state observation is automatically cancelled and
     * restarted whenever preferences change (e.g. when the user switches from Vosk to Whisper).
     * This prevents inner collectors from accumulating unbounded when preferences emit repeatedly.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeActiveModelState() {
        viewModelScope.launch {
            appSettings.preferences
                .flatMapLatest { prefs ->
                    if (!prefs.transcriptionEnabled) {
                        flowOf(
                            Triple<ModelInstallState?, String, Pair<Int, Boolean>>(
                                null,
                                "Speech Model",
                                0 to false,
                            )
                        )
                    } else {
                        val descriptor = activeModelDescriptor(prefs)
                        modelManager.stateOf(descriptor.id).map { installState ->
                            Triple(
                                installState,
                                descriptor.displayName,
                                Pair(descriptor.approxSizeMb, descriptor.wifiRecommended)
                            )
                        }
                    }
                }
                .collect { (installState, displayName, sizePair) ->
                    _uiState.update {
                        it.copy(
                            activeModelInstallState = installState,
                            activeModelDisplayName = displayName,
                            activeModelApproxSizeMb = sizePair.first,
                            activeModelWifiRecommended = sizePair.second,
                        )
                    }
                }
        }
    }

    /** Retries a failed model download for the currently active backend. */
    fun retryActiveModelInstall() {
        viewModelScope.launch {
            modelManager.retry(activeModelDescriptor(latestPreferences).id)
        }
    }

    // -------------------------------------------------------------------------
    // Session lifecycle
    // -------------------------------------------------------------------------

    private fun recreateSessionManager(prefs: UserPreferences) {
        liveStateJob?.cancel()
        sessionManager?.release()

        val mgr = createLiveSessionManager(prefs)
        sessionManager = mgr
        startWatchingLiveState(mgr, isFileSession = false, fileSessionUri = null)
    }

    private fun recreateFileSessionManager(prefs: UserPreferences, uri: Uri) {
        liveStateJob?.cancel()
        sessionManager?.release()

        val transcriber = createLocalTranscriber(prefs)

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
        mgr: SessionManager,
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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

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

        if (useRealtimeBackend(latestPreferences) && latestPreferences.realtimeWebSocketUrl.isBlank()) {
            _uiState.update {
                it.copy(errorMessage = "Set a realtime websocket URL in Settings before starting backend live mode.")
            }
            return
        }

        if (sessionManager == null || sessionManager?.state?.value == SessionState.Idle || sessionManager?.state?.value is SessionState.Error) {
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

    fun startWhisperBenchmark(uri: Uri) {
        if (_uiState.value.isSessionActive) {
            _uiState.update {
                it.copy(errorMessage = "Stop the current session before running a Whisper benchmark.")
            }
            return
        }

        val sourceLabel = displayNameFor(uri)
        _uiState.update {
            it.copy(
                whisperBenchmark = WhisperBenchmarkUiState(
                    isRunning = true,
                    sourceLabel = sourceLabel,
                    report = null,
                    errorMessage = null,
                )
            )
        }

        viewModelScope.launch {
            try {
                val report = whisperBenchmarkRunner.runComparisonForFile(
                    context = getApplication(),
                    audioFileUri = uri,
                    sourceLabel = sourceLabel,
                    configs = buildWhisperBenchmarkConfigs(),
                )
                val error = report.results.firstOrNull { it.runtimeError != null }?.runtimeError
                _uiState.update {
                    it.copy(
                        whisperBenchmark = WhisperBenchmarkUiState(
                            isRunning = false,
                            sourceLabel = sourceLabel,
                            report = report,
                            errorMessage = error,
                        )
                    )
                }
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        whisperBenchmark = WhisperBenchmarkUiState(
                            isRunning = false,
                            sourceLabel = sourceLabel,
                            report = null,
                            errorMessage = error.localizedMessage ?: "Benchmark run failed",
                        )
                    )
                }
            }
        }
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
        modelObserveJob?.cancel()
        sessionManager?.release()
    }


    private fun createLiveSessionManager(prefs: UserPreferences): SessionManager {
        if (useRealtimeBackend(prefs)) {
            return RealtimeSessionManager.create(
                config = RealtimeSessionConfig(websocketUrl = prefs.realtimeWebSocketUrl),
                feedbackDispatcher = VibrationFeedbackDispatcher(getApplication()),
                sessionRepository = repository,
                transcriptDebugEnabled = true,
            )
        }

        val transcriber = createLocalTranscriber(prefs)
        return SpeechCoachSessionManager.create(
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
    }

    private fun useRealtimeBackend(prefs: UserPreferences): Boolean =
        prefs.liveSessionBackend == LiveSessionBackend.RealtimeWebSocket
    private fun createLocalTranscriber(prefs: UserPreferences) = if (prefs.transcriptionEnabled) {
        val primaryTranscriber = if (prefs.preferWhisperBackend) {
            val descriptor = KnownModels.whisperDescriptor(prefs.whisperModelId)
            val whisperModelFile = KnownModels.whisperModelFile(
                getApplication<Application>().filesDir,
                descriptor.id,
            )
            WhisperCppLocalTranscriber.create(
                modelFile = whisperModelFile,
                modelId = descriptor.id,
                modelDisplayName = descriptor.displayName,
                chunkConfig = WhisperChunkingConfig.LiveDefault,
            )
        } else {
            VoskLocalTranscriber.create(getApplication())
        }
        RoutingLocalTranscriber(
            primaryTranscriber = primaryTranscriber,
            fallbackTranscriber = AndroidSpeechRecognizerTranscriber(getApplication())
        )
    } else {
        NoOpLocalTranscriber()
    }

    private fun buildWhisperBenchmarkConfigs(): List<WhisperBenchmarkConfig> {
        val filesDir = getApplication<Application>().filesDir
        val strategies = listOf(
            WhisperChunkingConfig.LiveDefault,
            WhisperChunkingConfig.LongerContext,
        )
        return KnownModels.whisperModels.flatMap { descriptor ->
            strategies.map { strategy ->
                WhisperBenchmarkConfig(
                    modelId = descriptor.id,
                    modelDisplayName = descriptor.displayName,
                    modelFile = KnownModels.whisperModelFile(filesDir, descriptor.id),
                    chunking = strategy,
                )
            }
        }
    }

    private fun displayNameFor(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment ?: uri.toString()
    }
}
