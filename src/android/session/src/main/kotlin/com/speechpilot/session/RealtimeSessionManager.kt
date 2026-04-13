package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import com.speechpilot.audio.MicrophoneCapture
import com.speechpilot.data.SessionRecord
import com.speechpilot.data.SessionRepository
import com.speechpilot.feedback.FeedbackDispatcher
import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.segmentation.VadFrameClassification
import com.speechpilot.transcription.TranscriptionBackend
import com.speechpilot.transcription.TranscriptionDiagnostics
import com.speechpilot.transcription.TranscriptionEngineStatus
import com.speechpilot.transcription.TranscriptionFailure
import com.speechpilot.vad.EnergyBasedVad
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt

data class RealtimeSessionConfig(
    val websocketUrl: String,
    val client: String = "android",
    val locale: String? = Locale.getDefault().toLanguageTag(),
)

class RealtimeSessionManager private constructor(
    private val config: RealtimeSessionConfig,
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    private val webSocketClient: RealtimeWebSocketClient = OkHttpRealtimeWebSocketClient(),
    private val feedbackDispatcher: FeedbackDispatcher? = null,
    private val sessionRepository: SessionRepository? = null,
    private val transcriptDebugEnabled: Boolean = true,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveSessionState())
    override val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var socketJob: Job? = null
    private var audioJob: Job? = null
    private var activeSessionId: String? = null
    private var activeMode: SessionMode = SessionMode.Active
    private var startedAtMs: Long = 0L
    private var nextChunkSequence: Int = 0
    private var fullTranscript = ""
    private var peakObservedWpm = 0.0
    private var expectedSocketClose = false
    private var summarySignal: CompletableDeferred<RealtimeSessionSummary?>? = null
    private var sessionFinalized = false
    private var speechFrameCount = 0
    private var silenceFrameCount = 0

    override suspend fun start(mode: SessionMode) {
        when (_state.value) {
            is SessionState.Starting, is SessionState.Active, is SessionState.Stopping -> return
            else -> Unit
        }

        val websocketUrl = config.websocketUrl.trim()
        if (websocketUrl.isBlank()) {
            failSession(IllegalStateException("Configure a realtime websocket URL in Settings before starting a live backend session."))
            return
        }

        startedAtMs = System.currentTimeMillis()
        activeMode = mode
        activeSessionId = UUID.randomUUID().toString()
        nextChunkSequence = 0
        fullTranscript = ""
        peakObservedWpm = 0.0
        expectedSocketClose = false
        sessionFinalized = false
        speechFrameCount = 0
        silenceFrameCount = 0
        summarySignal = CompletableDeferred()

        _state.value = SessionState.Starting
        _liveState.value = LiveSessionState(
            sessionState = SessionState.Starting,
            mode = mode,
            transcriptDebug = initialTranscriptDebug(),
            debugInfo = initialDebugInfo(lifecycle = "connecting"),
        )

        val openSignal = CompletableDeferred<Unit>()
        socketJob = managerScope.launch {
            try {
                webSocketClient.connect(websocketUrl).collect { event ->
                    when (event) {
                        RealtimeSocketEvent.Open -> {
                            _liveState.update { current ->
                                current.copy(
                                    debugInfo = current.debugInfo.copy(remoteLifecycle = "connected")
                                )
                            }
                            if (!openSignal.isCompleted) {
                                openSignal.complete(Unit)
                            }
                        }

                        is RealtimeSocketEvent.Message -> handleServerMessage(event.text)
                        is RealtimeSocketEvent.Closing -> {
                            _liveState.update { current ->
                                current.copy(
                                    debugInfo = current.debugInfo.copy(remoteLifecycle = "closing")
                                )
                            }
                        }

                        is RealtimeSocketEvent.Closed -> {
                            if (!expectedSocketClose && !sessionFinalized && state.value != SessionState.Idle) {
                                failSession(IllegalStateException("Realtime websocket disconnected."))
                            }
                        }

                        is RealtimeSocketEvent.Failure -> {
                            if (!expectedSocketClose) {
                                failSession(event.throwable)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!expectedSocketClose) {
                    failSession(error)
                }
            }
        }

        val connectionResult = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { openSignal.await() }
        if (connectionResult == null) {
            failSession(IllegalStateException("Timed out connecting to the realtime backend."))
            return
        }

        val sessionId = activeSessionId ?: return
        if (!webSocketClient.send(RealtimeProtocol.createSessionStart(sessionId, config.client, config.locale))) {
            failSession(IllegalStateException("Failed to send session.start to the realtime backend."))
            return
        }

        audioCapture.start()
        startAudioStreaming(sessionId)

        _state.value = SessionState.Active
        _liveState.update { current ->
            current.copy(
                sessionState = SessionState.Active,
                isListening = true,
                stats = SessionStats(startedAtMs = startedAtMs),
                transcriptDebug = current.transcriptDebug.copy(
                    status = TranscriptDebugStatus.WaitingForSpeech,
                    engineStatus = TranscriptionEngineStatus.Listening,
                    diagnostics = current.transcriptDebug.diagnostics.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Listening,
                        activeBackendStatus = TranscriptionEngineStatus.Listening,
                    ),
                ),
                debugInfo = current.debugInfo.copy(
                    remoteLifecycle = "session_started",
                    transcriptionStatus = TranscriptionEngineStatus.Listening,
                ),
            )
        }
    }

    override suspend fun stop() {
        when (_state.value) {
            is SessionState.Idle, is SessionState.Stopping -> return
            else -> Unit
        }

        _state.value = SessionState.Stopping
        _liveState.update { current ->
            current.copy(
                sessionState = SessionState.Stopping,
                isListening = false,
                debugInfo = current.debugInfo.copy(remoteLifecycle = "stopping")
            )
        }

        audioCapture.stop()
        audioJob?.cancelAndJoin()
        audioJob = null

        activeSessionId?.let { sessionId ->
            webSocketClient.send(RealtimeProtocol.createSessionStop(sessionId, "manual_stop"))
        }

        val summary = withTimeoutOrNull(SUMMARY_TIMEOUT_MS) { summarySignal?.await() }
        expectedSocketClose = true
        webSocketClient.disconnect(reason = "manual_stop")
        socketJob?.cancelAndJoin()
        socketJob = null

        finishSession(summary)
    }

    override fun release() {
        managerScope.launch {
            expectedSocketClose = true
            audioCapture.stop()
            audioJob?.cancelAndJoin()
            socketJob?.cancelAndJoin()
            webSocketClient.disconnect(reason = "release")
            webSocketClient.release()
        }
    }

    private fun startAudioStreaming(sessionId: String) {
        audioJob = managerScope.launch {
            val accumulator = PcmChunkAccumulator(sampleRate = MicrophoneCapture.SAMPLE_RATE)
            try {
                audioCapture.frames().collect { frame ->
                    updateFrameState(frame)
                    accumulator.append(frame)?.let { chunk ->
                        sendAudioChunk(sessionId, chunk)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failSession(error)
            } finally {
                accumulator.flush()?.let { chunk ->
                    sendAudioChunk(sessionId, chunk)
                }
            }
        }
    }

    private fun sendAudioChunk(sessionId: String, chunk: EncodedAudioChunk) {
        if (state.value !is SessionState.Active && state.value !is SessionState.Stopping) {
            return
        }
        val sent = webSocketClient.send(
            RealtimeProtocol.createAudioChunk(
                sessionId = sessionId,
                sequence = nextChunkSequence,
                sampleRateHz = chunk.sampleRateHz,
                durationMs = chunk.durationMs,
                dataBase64 = chunk.dataBase64,
            )
        )
        if (!sent) {
            failSession(IllegalStateException("Failed to stream audio chunk to the realtime backend."))
            return
        }
        nextChunkSequence += 1
    }

    private suspend fun handleServerMessage(message: String) {
        when (val event = RealtimeProtocol.parseServerEvent(message)) {
            is RealtimeServerEvent.TranscriptPartial -> handleTranscriptPartial(event)
            is RealtimeServerEvent.TranscriptFinal -> handleTranscriptFinal(event)
            is RealtimeServerEvent.PaceUpdate -> handlePaceUpdate(event.payload)
            is RealtimeServerEvent.FeedbackUpdate -> handleFeedbackUpdate(event.payload)
            is RealtimeServerEvent.DebugState -> handleDebugState(event.payload)
            is RealtimeServerEvent.SessionSummary -> {
                if (summarySignal?.isCompleted == false) {
                    summarySignal?.complete(event.payload)
                }
                finishSession(event.payload)
            }

            is RealtimeServerEvent.Error -> failSession(
                IllegalStateException(
                    buildString {
                        append(event.payload.message)
                        event.payload.detail?.takeIf { it.isNotBlank() }?.let {
                            append(": ")
                            append(it)
                        }
                    }
                )
            )

            null -> Unit
        }
    }

    private fun handleTranscriptPartial(event: RealtimeServerEvent.TranscriptPartial) {
        if (event.sessionId != activeSessionId) return
        val currentText = buildTranscriptPreview(event.text)
        _liveState.update { current ->
            current.copy(
                isSpeechDetected = current.isSpeechDetected || event.text.isNotBlank(),
                transcriptDebug = current.transcriptDebug.copy(
                    status = TranscriptDebugStatus.PartialAvailable,
                    transcriptText = currentText,
                    partialTranscriptPresent = event.text.isNotBlank(),
                    wpmPendingFinalRecognition = current.transcriptDebug.finalizedWordCount == 0 && event.text.isNotBlank(),
                    lastUpdateAtMs = System.currentTimeMillis(),
                    diagnostics = current.transcriptDebug.diagnostics.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Listening,
                        activeBackendStatus = TranscriptionEngineStatus.Listening,
                        totalTranscriptUpdatesEmitted = current.transcriptDebug.diagnostics.totalTranscriptUpdatesEmitted + 1,
                        lastSuccessfulTranscriptAtMs = System.currentTimeMillis(),
                    )
                )
            )
        }
    }

    private fun handleTranscriptFinal(event: RealtimeServerEvent.TranscriptFinal) {
        if (event.sessionId != activeSessionId) return
        fullTranscript = buildString {
            if (fullTranscript.isNotBlank()) {
                append(fullTranscript)
                append('\n')
            }
            append(event.segment.text)
        }.trim()
        _liveState.update { current ->
            current.copy(
                isSpeechDetected = true,
                transcriptDebug = current.transcriptDebug.copy(
                    status = TranscriptDebugStatus.FinalAvailable,
                    transcriptText = fullTranscript,
                    partialTranscriptPresent = false,
                    finalizedWordCount = current.transcriptDebug.finalizedWordCount + event.segment.wordCount,
                    rollingWordCount = current.transcriptDebug.finalizedWordCount + event.segment.wordCount,
                    wpmPendingFinalRecognition = false,
                    lastUpdateAtMs = System.currentTimeMillis(),
                    diagnostics = current.transcriptDebug.diagnostics.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Listening,
                        activeBackendStatus = TranscriptionEngineStatus.Listening,
                        totalTranscriptUpdatesEmitted = current.transcriptDebug.diagnostics.totalTranscriptUpdatesEmitted + 1,
                        lastSuccessfulTranscriptAtMs = System.currentTimeMillis(),
                    ),
                ),
                stats = current.stats.copy(segmentCount = current.stats.segmentCount + 1),
                debugInfo = current.debugInfo.copy(
                    finalizedSegmentsCount = current.debugInfo.finalizedSegmentsCount + 1,
                )
            )
        }
    }

    private fun handlePaceUpdate(update: RealtimePaceUpdate) {
        if (update.sessionId != activeSessionId) return
        peakObservedWpm = maxOf(peakObservedWpm, update.wordsPerMinute)
        val source = paceSourceFromRemote(update.source)
        _liveState.update { current ->
            current.copy(
                currentWpm = update.wordsPerMinute.toFloat(),
                smoothedWpm = update.wordsPerMinute.toFloat(),
                transcriptDebug = current.transcriptDebug.copy(
                    rollingWordCount = maxOf(current.transcriptDebug.rollingWordCount, update.totalWords),
                    finalizedWordCount = maxOf(current.transcriptDebug.finalizedWordCount, update.totalWords),
                    rollingWpm = update.wordsPerMinute.toFloat(),
                    wpmPendingFinalRecognition = false,
                ),
                stats = current.stats.copy(
                    durationMs = (update.speakingDurationMs + update.silenceDurationMs).toLong(),
                    totalSpeechActiveDurationMs = update.speakingDurationMs.toLong(),
                    averageEstimatedWpm = update.wordsPerMinute,
                    peakEstimatedWpm = peakObservedWpm,
                ),
                debugInfo = current.debugInfo.copy(
                    activePaceSource = source,
                    paceSourceReason = "backend:${update.source}",
                    fallbackActive = source == PaceSignalSource.Heuristic,
                    transcriptReadyForDecision = update.totalWords > 0,
                    decisionWpm = update.wordsPerMinute,
                    transcriptWpm = if (source == PaceSignalSource.Transcript) update.wordsPerMinute else current.debugInfo.transcriptWpm,
                    heuristicWpm = if (source == PaceSignalSource.Heuristic) update.wordsPerMinute else current.debugInfo.heuristicWpm,
                )
            )
        }
    }

    private fun handleFeedbackUpdate(update: RealtimeFeedbackUpdate) {
        if (update.sessionId != activeSessionId) return
        val feedback = feedbackEventFromRemote(update.decision) ?: return
        if (activeMode == SessionMode.Active) {
            feedbackDispatcher?.dispatch(feedback)
        }
        _liveState.update { current ->
            current.copy(
                latestFeedback = feedback,
                alertActive = feedback == FeedbackEvent.SlowDown || feedback == FeedbackEvent.SpeedUp,
                debugInfo = current.debugInfo.copy(
                    lastDecisionReason = "backend:${update.reason}",
                    remoteFeedbackCount = current.debugInfo.remoteFeedbackCount + 1,
                )
            )
        }
    }

    private fun handleDebugState(debug: RealtimeDebugState) {
        _liveState.update { current ->
            val backend = if (debug.activeProvider != null) TranscriptionBackend.RemoteRealtime else current.transcriptDebug.activeBackend
            val engineStatus = engineStatusFromLifecycle(debug.lifecycle)
            current.copy(
                currentWpm = debug.wordsPerMinute?.toFloat() ?: current.currentWpm,
                smoothedWpm = debug.wordsPerMinute?.toFloat() ?: current.smoothedWpm,
                transcriptDebug = current.transcriptDebug.copy(
                    activeBackend = backend,
                    engineStatus = engineStatus,
                    diagnostics = current.transcriptDebug.diagnostics.copy(
                        selectedBackend = TranscriptionBackend.RemoteRealtime,
                        activeBackend = backend,
                        selectedModelDisplayName = debug.activeProvider ?: "Realtime backend",
                        activeModelDisplayName = debug.activeProvider ?: "Realtime backend",
                        selectedBackendStatus = engineStatus,
                        activeBackendStatus = engineStatus,
                        fallbackActive = false,
                    ),
                ),
                debugInfo = current.debugInfo.copy(
                    transcriptionStatus = engineStatus,
                    remoteLifecycle = debug.lifecycle,
                    remoteProvider = debug.activeProvider,
                    remoteChunksReceived = debug.chunksReceived,
                    remotePartialUpdates = debug.partialUpdates,
                    remoteFeedbackCount = debug.feedbackCount,
                    lastDecisionReason = debug.lastFeedbackReason?.let { "backend:$it" } ?: current.debugInfo.lastDecisionReason,
                    decisionWpm = debug.wordsPerMinute ?: current.debugInfo.decisionWpm,
                    transcriptWpm = if (current.debugInfo.activePaceSource == PaceSignalSource.Transcript) {
                        debug.wordsPerMinute ?: current.debugInfo.transcriptWpm
                    } else {
                        current.debugInfo.transcriptWpm
                    },
                    heuristicWpm = if (current.debugInfo.activePaceSource == PaceSignalSource.Heuristic) {
                        debug.wordsPerMinute ?: current.debugInfo.heuristicWpm
                    } else {
                        current.debugInfo.heuristicWpm
                    },
                    finalizedSegmentsCount = debug.finalSegments,
                )
            )
        }
    }

    private suspend fun finishSession(summary: RealtimeSessionSummary?) {
        if (sessionFinalized) return
        sessionFinalized = true

        if (summary != null) {
            persistSessionSummary(summary)
        }

        expectedSocketClose = true
        audioCapture.stop()
        audioJob?.cancelAndJoin()
        audioJob = null
        webSocketClient.disconnect(reason = "session_finished")
        socketJob?.cancelAndJoin()
        socketJob = null

        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
        _state.value = SessionState.Idle
        activeSessionId = null
    }

    private fun failSession(error: Throwable) {
        if (sessionFinalized) return
        expectedSocketClose = true
        summarySignal?.complete(null)
        _state.value = SessionState.Error(error)
        _liveState.update { current ->
            current.copy(
                sessionState = SessionState.Error(error),
                isListening = false,
                debugInfo = current.debugInfo.copy(
                    remoteLifecycle = "error",
                    transcriptionStatus = TranscriptionEngineStatus.Error,
                ),
                transcriptDebug = current.transcriptDebug.copy(
                    status = TranscriptDebugStatus.Error,
                    engineStatus = TranscriptionEngineStatus.Error,
                    diagnostics = current.transcriptDebug.diagnostics.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Error,
                        activeBackendStatus = TranscriptionEngineStatus.Error,
                        lastTranscriptError = TranscriptionFailure(
                            code = "realtime-session-error",
                            message = error.localizedMessage ?: "Realtime session error",
                        )
                    )
                )
            )
        }
        managerScope.launch {
            audioCapture.stop()
            audioJob?.cancelAndJoin()
            audioJob = null
            webSocketClient.disconnect(reason = "session_error")
        }
    }

    private suspend fun persistSessionSummary(summary: RealtimeSessionSummary) {
        val repository = sessionRepository ?: return
        if (startedAtMs == 0L) return
        repository.insert(
            SessionRecord(
                startedAtMs = startedAtMs,
                endedAtMs = startedAtMs + summary.durationMs,
                durationMs = summary.durationMs.toLong(),
                totalSpeechActiveDurationMs = summary.speakingDurationMs.toLong(),
                segmentCount = summary.transcriptSegments,
                averageEstimatedWpm = summary.averageWpm ?: liveState.value.currentWpm.toDouble(),
                peakEstimatedWpm = peakObservedWpm,
            )
        )
    }

    private fun updateFrameState(frame: AudioFrame) {
        val rms = computeFrameRms(frame.samples)
        val speechActive = rms >= EnergyBasedVad.DEFAULT_THRESHOLD
        if (speechActive) {
            speechFrameCount += frame.samples.size
            silenceFrameCount = 0
        } else {
            silenceFrameCount += frame.samples.size
            speechFrameCount = 0
        }
        _liveState.update { current ->
            current.copy(
                isSpeechActive = speechActive,
                isSpeechDetected = current.isSpeechDetected || speechActive,
                micLevel = (rms / MAX_DISPLAY_RMS).coerceIn(0.0, 1.0).toFloat(),
                debugInfo = current.debugInfo.copy(
                    vadFrameRms = rms,
                    vadThreshold = EnergyBasedVad.DEFAULT_THRESHOLD,
                    vadFrameClassification = if (speechActive) VadFrameClassification.Speech else VadFrameClassification.Silence,
                    isSegmentOpen = speechActive,
                    openSegmentFrameCount = speechFrameCount,
                    openSegmentSilenceFrameCount = silenceFrameCount,
                )
            )
        }
    }

    private fun initialTranscriptDebug(): TranscriptDebugState {
        val diagnostics = TranscriptionDiagnostics(
            selectedBackend = TranscriptionBackend.RemoteRealtime,
            activeBackend = TranscriptionBackend.RemoteRealtime,
            selectedModelDisplayName = "Realtime backend",
            activeModelDisplayName = "Realtime backend",
            selectedBackendStatus = TranscriptionEngineStatus.Disabled,
            activeBackendStatus = TranscriptionEngineStatus.Disabled,
        )
        return TranscriptDebugState(
            debugEnabled = transcriptDebugEnabled,
            status = if (transcriptDebugEnabled) TranscriptDebugStatus.Listening else TranscriptDebugStatus.Disabled,
            engineStatus = TranscriptionEngineStatus.Disabled,
            activeBackend = TranscriptionBackend.RemoteRealtime,
            diagnostics = diagnostics,
        )
    }

    private fun initialDebugInfo(lifecycle: String): DebugPipelineInfo = DebugPipelineInfo(
        transcriptionStatus = TranscriptionEngineStatus.Disabled,
        remoteLifecycle = lifecycle,
        remoteProvider = null,
    )

    private fun buildTranscriptPreview(partialText: String): String = when {
        fullTranscript.isBlank() -> partialText
        partialText.isBlank() -> fullTranscript
        else -> "$fullTranscript\n$partialText"
    }.trim()

    private fun feedbackEventFromRemote(decision: String): FeedbackEvent? = when (decision) {
        "slow_down" -> FeedbackEvent.SlowDown
        "speed_up" -> FeedbackEvent.SpeedUp
        "good_pace" -> FeedbackEvent.OnTarget
        else -> null
    }

    private fun paceSourceFromRemote(source: String): PaceSignalSource = when {
        source.contains("transcript", ignoreCase = true) || source.contains("text", ignoreCase = true) -> PaceSignalSource.Transcript
        source.contains("heuristic", ignoreCase = true) || source.contains("fallback", ignoreCase = true) -> PaceSignalSource.Heuristic
        else -> PaceSignalSource.Transcript
    }

    private fun engineStatusFromLifecycle(lifecycle: String): TranscriptionEngineStatus = when (lifecycle) {
        "connected", "session_started", "receiving_audio", "processing", "ready" -> TranscriptionEngineStatus.Listening
        "connecting" -> TranscriptionEngineStatus.InitializingModel
        "error", "failed" -> TranscriptionEngineStatus.Error
        else -> TranscriptionEngineStatus.Listening
    }

    private fun computeFrameRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples.size)
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 5_000L
        private const val SUMMARY_TIMEOUT_MS = 2_000L
        private const val MAX_DISPLAY_RMS = 5_000.0

        fun create(
            config: RealtimeSessionConfig,
            feedbackDispatcher: FeedbackDispatcher? = null,
            sessionRepository: SessionRepository? = null,
            transcriptDebugEnabled: Boolean = true,
        ): RealtimeSessionManager = RealtimeSessionManager(
            config = config,
            feedbackDispatcher = feedbackDispatcher,
            sessionRepository = sessionRepository,
            transcriptDebugEnabled = transcriptDebugEnabled,
        )
    }
}

private data class EncodedAudioChunk(
    val sampleRateHz: Int,
    val durationMs: Int,
    val dataBase64: String,
)

private class PcmChunkAccumulator(
    private val sampleRate: Int,
    private val targetChunkDurationMs: Int = 480,
) {
    private val output = ByteArrayOutputStream()
    private var sampleCount = 0

    fun append(frame: AudioFrame): EncodedAudioChunk? {
        for (sample in frame.samples) {
            output.write(sample.toInt() and 0xFF)
            output.write((sample.toInt() shr 8) and 0xFF)
        }
        sampleCount += frame.samples.size
        return if (durationMs() >= targetChunkDurationMs) {
            flush()
        } else {
            null
        }
    }

    fun flush(): EncodedAudioChunk? {
        if (sampleCount == 0) return null
        val bytes = output.toByteArray()
        val durationMs = durationMs()
        output.reset()
        sampleCount = 0
        return EncodedAudioChunk(
            sampleRateHz = sampleRate,
            durationMs = durationMs,
            dataBase64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    private fun durationMs(): Int = ((sampleCount.toDouble() / sampleRate.toDouble()) * 1000.0).toInt()
}