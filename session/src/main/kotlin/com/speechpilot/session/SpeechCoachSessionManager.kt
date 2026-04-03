package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import com.speechpilot.audio.FileAudioCapture
import com.speechpilot.audio.MicrophoneCapture
import com.speechpilot.data.SessionRecord
import com.speechpilot.data.SessionRepository
import com.speechpilot.feedback.FeedbackDecision
import com.speechpilot.feedback.FeedbackDispatcher
import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.pace.PaceEstimator
import com.speechpilot.pace.RollingPaceWindow
import com.speechpilot.pace.RollingWindowPaceEstimator
import com.speechpilot.segmentation.SpeechSegmenter
import com.speechpilot.segmentation.VadSpeechSegmenter
import com.speechpilot.segmentation.VadFrameClassification
import com.speechpilot.transcription.LocalTranscriber
import com.speechpilot.transcription.NoOpLocalTranscriber
import com.speechpilot.transcription.RollingTranscriptWpmCalculator
import com.speechpilot.transcription.TranscriptionBackend
import com.speechpilot.transcription.TranscriptionEngineStatus
import com.speechpilot.vad.EnergyBasedVad
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Central coordinator for the speech coaching session lifecycle.
 *
 * @param audioFileUri Non-null for file-based sessions. Stored in the persisted [SessionRecord]
 *   so the file can be re-analyzed from session history.
 */
class SpeechCoachSessionManager(
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    private val segmenter: SpeechSegmenter = VadSpeechSegmenter(EnergyBasedVad()),
    private val paceEstimator: PaceEstimator = RollingWindowPaceEstimator(),
    private val rollingPaceWindow: RollingPaceWindow = RollingPaceWindow(),
    private val feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
    private val localTranscriber: LocalTranscriber = NoOpLocalTranscriber(),
    private val transcriptWpmCalculator: RollingTranscriptWpmCalculator = RollingTranscriptWpmCalculator(),
    private val transcriptDebugEnabled: Boolean = false,
    private val feedbackDispatcher: FeedbackDispatcher? = null,
    private val sessionRepository: SessionRepository? = null,
    private val audioFileUri: String? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveSessionState())
    override val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var pipelineJob: Job? = null
    private var transcriptionJob: Job? = null

    override suspend fun start(mode: SessionMode) {
        when (_state.value) {
            is SessionState.Starting, is SessionState.Active, is SessionState.Stopping -> return
            else -> Unit
        }
        _state.value = SessionState.Starting
        paceEstimator.reset()
        rollingPaceWindow.reset()
        transcriptWpmCalculator.reset()

        pipelineJob = managerScope.launch {
            val sessionStartMs = System.currentTimeMillis()
            _state.value = SessionState.Active
            _liveState.update {
                val debugTarget = (feedbackDecision as? ThresholdFeedbackDecision)?.currentTargetWpm()
                    ?: it.debugInfo.targetWpm
                val vadThreshold = (segmenter as? VadSpeechSegmenter)?.configuredVadThreshold
                    ?: EnergyBasedVad.DEFAULT_THRESHOLD
                it.copy(
                    sessionState = SessionState.Active,
                    mode = mode,
                    isListening = true,
                    stats = SessionStats(startedAtMs = sessionStartMs),
                    transcriptDebug = it.transcriptDebug.copy(
                        debugEnabled = transcriptDebugEnabled,
                        status = resolveTranscriptDebugStatus(
                            debugEnabled = transcriptDebugEnabled,
                            engineStatus = localTranscriber.status.value,
                            isSessionListening = true,
                            partialTranscriptPresent = false,
                            finalizedWordCount = 0
                        ),
                        engineStatus = localTranscriber.status.value,
                        activeBackend = localTranscriber.activeBackend.value,
                        transcriptText = "",
                        partialTranscriptPresent = false,
                        finalizedWordCount = 0,
                        rollingWordCount = 0,
                        rollingWpm = 0f,
                        wpmPendingFinalRecognition = false,
                        lastUpdateAtMs = null
                    ),
                    debugInfo = it.debugInfo.copy(
                        targetWpm = debugTarget,
                        transcriptionStatus = localTranscriber.status.value,
                        vadThreshold = vadThreshold,
                        activePaceSource = PaceSignalSource.None,
                        paceSourceReason = "session-started-no-signal",
                        fallbackActive = false,
                        transcriptReadyForDecision = false,
                        decisionWpm = 0.0,
                        transcriptWpm = 0.0,
                        heuristicWpm = 0.0
                    )
                )
            }
            audioCapture.start()
            startTranscriptionCollector()

            // Share frames between the frame-level activity monitor and the segmenter so
            // AudioRecord is only opened once.
            val sharedFrames = audioCapture.frames()
                .shareIn(this, SharingStarted.Eagerly, replay = 0)

            // Frame-level monitor: updates micLevel and isSpeechActive at a ~100 ms cadence.
            // This runs in parallel with segmentation and keeps the UI feeling alive even when
            // no complete speech segment has been emitted yet.
            launch {
                var frameCount = 0
                val vadThreshold = (segmenter as? VadSpeechSegmenter)?.configuredVadThreshold
                    ?: EnergyBasedVad.DEFAULT_THRESHOLD
                sharedFrames.collect { frame ->
                    frameCount++
                    if (frameCount % FRAME_LEVEL_UPDATE_INTERVAL == 0) {
                        val rms = computeFrameRms(frame.samples)
                        val level = (rms / MAX_DISPLAY_RMS).coerceIn(0.0, 1.0).toFloat()
                        val speechActive = rms >= vadThreshold
                        _liveState.update {
                            it.copy(
                                micLevel = level,
                                isSpeechActive = speechActive,
                                debugInfo = it.debugInfo.copy(vadFrameRms = rms, vadThreshold = vadThreshold)
                            )
                        }
                    }
                }
            }

            try {
                (segmenter as? VadSpeechSegmenter)?.onDebugSnapshot = { snapshot ->
                    _liveState.update { current ->
                        current.copy(
                            isSpeechActive = snapshot.frameClassification == VadFrameClassification.Speech,
                            debugInfo = current.debugInfo.copy(
                                vadFrameRms = snapshot.frameRms,
                                vadThreshold = snapshot.vadThreshold ?: current.debugInfo.vadThreshold,
                                vadFrameClassification = snapshot.frameClassification,
                                isSegmentOpen = snapshot.isSegmentOpen,
                                openSegmentFrameCount = snapshot.openSegmentFrameCount,
                                openSegmentSilenceFrameCount = snapshot.openSegmentSilenceFrameCount,
                                finalizedSegmentsCount = snapshot.finalizedSegmentsCount
                            )
                        )
                    }
                }
                segmenter.segment(sharedFrames).collect { segment ->
                    val metrics = paceEstimator.estimate(segment)
                    rollingPaceWindow.update(metrics)
                    val durationMs = System.currentTimeMillis() - sessionStartMs
                    val heuristicWpm = rollingPaceWindow.smoothedEstimatedWpm()
                        .takeIf { it > 0.0 } ?: metrics.estimatedWpm
                    val currentState = _liveState.value
                    val paceSelection = selectPaceSignal(
                        transcriptEnabled = currentState.transcriptDebug.debugEnabled,
                        transcriptStatus = currentState.transcriptDebug.status,
                        transcriptWpm = currentState.transcriptDebug.rollingWpm.toDouble(),
                        transcriptFinalizedWordCount = currentState.transcriptDebug.finalizedWordCount,
                        transcriptRollingWordCount = currentState.transcriptDebug.rollingWordCount,
                        heuristicWpm = heuristicWpm
                    )
                    val feedback = feedbackDecision.evaluate(
                        asDecisionMetrics(
                            selection = paceSelection,
                            windowDurationMs = metrics.windowDurationMs
                        )
                    )

                    if (feedback != null && _liveState.value.mode == SessionMode.Active) {
                        feedbackDispatcher?.dispatch(feedback)
                    }

                    _liveState.update { current ->
                        val newStats = current.stats.copy(
                            durationMs = durationMs,
                            totalSpeechActiveDurationMs =
                                current.stats.totalSpeechActiveDurationMs + segment.durationMs,
                            segmentCount = current.stats.segmentCount + 1,
                            averageEstimatedWpm = rollingPaceWindow.averageEstimatedWpm(),
                            peakEstimatedWpm = rollingPaceWindow.peakEstimatedWpm()
                        )
                        val newAlertActive = when (feedback) {
                            FeedbackEvent.SlowDown, FeedbackEvent.SpeedUp -> true
                            FeedbackEvent.OnTarget -> false
                            null -> current.alertActive
                        }
                        val engine = feedbackDecision as? ThresholdFeedbackDecision
                        val newDebugInfo = DebugPipelineInfo(
                            targetWpm = engine?.currentTargetWpm() ?: current.debugInfo.targetWpm,
                            lastDecisionReason = engine?.lastDecisionReason ?: current.debugInfo.lastDecisionReason,
                            isInCooldown = engine?.isCooldownActive() ?: false,
                            activePaceSource = paceSelection.source,
                            paceSourceReason = paceSelection.reason,
                            fallbackActive = paceSelection.fallbackActive,
                            transcriptReadyForDecision = paceSelection.transcriptReady,
                            decisionWpm = paceSelection.selectedWpm,
                            transcriptWpm = current.transcriptDebug.rollingWpm.toDouble(),
                            heuristicWpm = heuristicWpm,
                            transcriptionStatus = current.debugInfo.transcriptionStatus,
                            vadFrameRms = current.debugInfo.vadFrameRms,
                            vadThreshold = current.debugInfo.vadThreshold,
                            vadFrameClassification = current.debugInfo.vadFrameClassification,
                            isSegmentOpen = current.debugInfo.isSegmentOpen,
                            openSegmentFrameCount = current.debugInfo.openSegmentFrameCount,
                            openSegmentSilenceFrameCount = current.debugInfo.openSegmentSilenceFrameCount,
                            finalizedSegmentsCount = newStats.segmentCount
                        )
                        current.copy(
                            isSpeechDetected = true,
                            currentWpm = metrics.estimatedWpm.toFloat(),
                            smoothedWpm = rollingPaceWindow.smoothedEstimatedWpm().toFloat(),
                            latestFeedback = feedback ?: current.latestFeedback,
                            alertActive = newAlertActive,
                            stats = newStats,
                            debugInfo = newDebugInfo
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SessionState.Error(e)
                _liveState.update { it.copy(isListening = false) }
            } finally {
                (segmenter as? VadSpeechSegmenter)?.onDebugSnapshot = null
                transcriptionJob?.cancelAndJoin()
                transcriptionJob = null
                localTranscriber.stop()
                audioCapture.stop()
            }

            // Auto-finalize when the audio source is exhausted naturally (e.g. file sessions
            // where the flow completes once all frames are emitted). This path is not reached
            // on CancellationException — stop() handles that path instead.
            if (_state.value == SessionState.Active) {
                val finalLiveState = _liveState.value
                _state.value = SessionState.Stopping
                persistSessionSummary(finalLiveState)
                paceEstimator.reset()
                rollingPaceWindow.reset()
                transcriptWpmCalculator.reset()
                _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
                _state.value = SessionState.Idle
            }
        }
    }

    override suspend fun stop() {
        when (_state.value) {
            is SessionState.Idle, is SessionState.Stopping -> return
            else -> Unit
        }
        _state.value = SessionState.Stopping
        pipelineJob?.cancelAndJoin()
        pipelineJob = null

        val finalState = _liveState.value
        persistSessionSummary(finalState)

        paceEstimator.reset()
        rollingPaceWindow.reset()
        transcriptWpmCalculator.reset()
        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
        _state.value = SessionState.Idle
    }

    fun release() {
        managerScope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /** Update mic level / speech-active state every N frames (~32 ms/frame → every ~100 ms). */
        internal const val FRAME_LEVEL_UPDATE_INTERVAL = 3
        /** RMS ceiling used to normalise micLevel to [0, 1]. Covers typical speech levels. */
        internal const val MAX_DISPLAY_RMS = 5_000.0

        fun create(
            feedbackDispatcher: FeedbackDispatcher? = null,
            sessionRepository: SessionRepository? = null,
            feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
            localTranscriber: LocalTranscriber = NoOpLocalTranscriber(),
            transcriptDebugEnabled: Boolean = false
        ): SpeechCoachSessionManager = SpeechCoachSessionManager(
            feedbackDispatcher = feedbackDispatcher,
            sessionRepository = sessionRepository,
            feedbackDecision = feedbackDecision,
            localTranscriber = localTranscriber,
            transcriptDebugEnabled = transcriptDebugEnabled
        )

        /**
         * Creates a [SpeechCoachSessionManager] that analyses a pre-recorded audio file instead
         * of the live microphone.
         *
         * The [audioFileUri] is persisted in [SessionRecord] so the file can be re-analysed from
         * session history. The session auto-finalizes when the file is fully processed.
         *
         * Requires read access to [audioFileUri] via the content resolver. If the file was
         * selected via [android.content.Intent.ACTION_OPEN_DOCUMENT], take a persistable URI
         * permission before calling this factory so that re-analysis from history works across
         * app restarts.
         */
        fun createForFile(
            context: Context,
            audioFileUri: Uri,
            feedbackDispatcher: FeedbackDispatcher? = null,
            sessionRepository: SessionRepository? = null,
            feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
            localTranscriber: LocalTranscriber = NoOpLocalTranscriber(),
            transcriptDebugEnabled: Boolean = false
        ): SpeechCoachSessionManager = SpeechCoachSessionManager(
            audioCapture = FileAudioCapture(context, audioFileUri),
            feedbackDispatcher = feedbackDispatcher,
            sessionRepository = sessionRepository,
            feedbackDecision = feedbackDecision,
            localTranscriber = localTranscriber,
            transcriptDebugEnabled = transcriptDebugEnabled,
            audioFileUri = audioFileUri.toString()
        )
    }

    private fun computeFrameRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size)
    }

    private suspend fun startTranscriptionCollector() {
        try {
            localTranscriber.start()
            transcriptionJob = managerScope.launch {
                launch {
                    localTranscriber.activeBackend.collect { backend ->
                        _liveState.update { current ->
                            current.copy(
                                transcriptDebug = current.transcriptDebug.copy(
                                    activeBackend = backend
                                )
                            )
                        }
                    }
                }
                launch {
                    localTranscriber.status.collect { status ->
                        _liveState.update { current ->
                            val transcript = current.transcriptDebug
                            val heuristicWpm = if (current.smoothedWpm > 0f) {
                                current.smoothedWpm.toDouble()
                            } else {
                                current.currentWpm.toDouble()
                            }
                            val paceSelection = selectPaceSignal(
                                transcriptEnabled = transcript.debugEnabled,
                                transcriptStatus = transcript.status,
                                transcriptWpm = transcript.rollingWpm.toDouble(),
                                transcriptFinalizedWordCount = transcript.finalizedWordCount,
                                transcriptRollingWordCount = transcript.rollingWordCount,
                                heuristicWpm = heuristicWpm
                            )
                            current.copy(
                                debugInfo = current.debugInfo.copy(
                                    transcriptionStatus = status,
                                    activePaceSource = paceSelection.source,
                                    paceSourceReason = paceSelection.reason,
                                    fallbackActive = paceSelection.fallbackActive,
                                    transcriptReadyForDecision = paceSelection.transcriptReady,
                                    decisionWpm = paceSelection.selectedWpm,
                                    transcriptWpm = transcript.rollingWpm.toDouble(),
                                    heuristicWpm = heuristicWpm
                                ),
                                transcriptDebug = transcript.copy(
                                    engineStatus = status,
                                    status = resolveTranscriptDebugStatus(
                                        debugEnabled = transcript.debugEnabled,
                                        engineStatus = status,
                                        isSessionListening = current.isListening,
                                        partialTranscriptPresent = transcript.partialTranscriptPresent,
                                        finalizedWordCount = transcript.finalizedWordCount
                                    )
                                )
                            )
                        }
                    }
                }
                localTranscriber.updates.collect { update ->
                    val snapshot = transcriptWpmCalculator.onUpdate(update)
                    _liveState.update { current ->
                        val debugEnabled = current.transcriptDebug.debugEnabled
                        val status = resolveTranscriptDebugStatus(
                            debugEnabled = debugEnabled,
                            engineStatus = current.transcriptDebug.engineStatus,
                            isSessionListening = current.isListening,
                            partialTranscriptPresent = snapshot.partialTranscriptPresent,
                            finalizedWordCount = snapshot.finalizedWordCount
                        )
                        val heuristicWpm = if (current.smoothedWpm > 0f) {
                            current.smoothedWpm.toDouble()
                        } else {
                            current.currentWpm.toDouble()
                        }
                        val paceSelection = selectPaceSignal(
                            transcriptEnabled = debugEnabled,
                            transcriptStatus = status,
                            transcriptWpm = snapshot.rollingWpm,
                            transcriptFinalizedWordCount = snapshot.finalizedWordCount,
                            transcriptRollingWordCount = snapshot.rollingWordCount,
                            heuristicWpm = heuristicWpm
                        )
                        current.copy(
                            debugInfo = current.debugInfo.copy(
                                activePaceSource = paceSelection.source,
                                paceSourceReason = paceSelection.reason,
                                fallbackActive = paceSelection.fallbackActive,
                                transcriptReadyForDecision = paceSelection.transcriptReady,
                                decisionWpm = paceSelection.selectedWpm,
                                transcriptWpm = snapshot.rollingWpm,
                                heuristicWpm = heuristicWpm
                            ),
                            transcriptDebug = current.transcriptDebug.copy(
                                status = status,
                                transcriptText = snapshot.transcriptPreview,
                                partialTranscriptPresent = snapshot.partialTranscriptPresent,
                                finalizedWordCount = snapshot.finalizedWordCount,
                                rollingWordCount = snapshot.rollingWordCount,
                                rollingWpm = snapshot.rollingWpm.toFloat(),
                                wpmPendingFinalRecognition = snapshot.partialTranscriptPresent &&
                                    snapshot.finalizedWordCount == 0,
                                lastUpdateAtMs = update.receivedAtMs
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
                _liveState.update {
                val transcript = it.transcriptDebug
                it.copy(
                    debugInfo = it.debugInfo.copy(transcriptionStatus = TranscriptionEngineStatus.Unavailable),
                    transcriptDebug = transcript.copy(
                        engineStatus = TranscriptionEngineStatus.Unavailable,
                        status = resolveTranscriptDebugStatus(
                            debugEnabled = transcript.debugEnabled,
                            engineStatus = TranscriptionEngineStatus.Unavailable,
                            isSessionListening = it.isListening,
                            partialTranscriptPresent = transcript.partialTranscriptPresent,
                            finalizedWordCount = transcript.finalizedWordCount
                        )
                    )
                )
            }
        }
    }

    private fun persistSessionSummary(state: LiveSessionState) {
        val repo = sessionRepository ?: return
        val stats = state.stats
        if (stats.startedAtMs == 0L) return
        val endedAtMs = System.currentTimeMillis()
        managerScope.launch {
            repo.insert(
                SessionRecord(
                    startedAtMs = stats.startedAtMs,
                    endedAtMs = endedAtMs,
                    durationMs = endedAtMs - stats.startedAtMs,
                    totalSpeechActiveDurationMs = stats.totalSpeechActiveDurationMs,
                    segmentCount = stats.segmentCount,
                    averageEstimatedWpm = stats.averageEstimatedWpm,
                    peakEstimatedWpm = stats.peakEstimatedWpm,
                    audioFileUri = audioFileUri
                )
            )
        }
    }
}
