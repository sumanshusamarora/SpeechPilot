package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
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
import com.speechpilot.transcription.LocalTranscriber
import com.speechpilot.transcription.NoOpLocalTranscriber
import com.speechpilot.transcription.RollingTranscriptWpmCalculator
import com.speechpilot.transcription.TranscriptionEngineStatus
import com.speechpilot.vad.EnergyBasedVad
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
 */
class SpeechCoachSessionManager(
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    private val segmenter: SpeechSegmenter = VadSpeechSegmenter(EnergyBasedVad()),
    private val paceEstimator: PaceEstimator = RollingWindowPaceEstimator(),
    private val rollingPaceWindow: RollingPaceWindow = RollingPaceWindow(),
    private val feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
    private val localTranscriber: LocalTranscriber = NoOpLocalTranscriber(),
    private val transcriptWpmCalculator: RollingTranscriptWpmCalculator = RollingTranscriptWpmCalculator(),
    private val feedbackDispatcher: FeedbackDispatcher? = null,
    private val sessionRepository: SessionRepository? = null,
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
                it.copy(
                    sessionState = SessionState.Active,
                    mode = mode,
                    isListening = true,
                    stats = SessionStats(startedAtMs = sessionStartMs),
                    debugInfo = it.debugInfo.copy(transcriptionStatus = localTranscriber.status.value)
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
                sharedFrames.collect { frame ->
                    frameCount++
                    if (frameCount % FRAME_LEVEL_UPDATE_INTERVAL == 0) {
                        val rms = computeFrameRms(frame.samples)
                        val level = (rms / MAX_DISPLAY_RMS).coerceIn(0.0, 1.0).toFloat()
                        val speechActive = rms >= VAD_SPEECH_THRESHOLD
                        _liveState.update { it.copy(micLevel = level, isSpeechActive = speechActive) }
                    }
                }
            }

            try {
                segmenter.segment(sharedFrames).collect { segment ->
                    val metrics = paceEstimator.estimate(segment)
                    rollingPaceWindow.update(metrics)
                    val feedback = feedbackDecision.evaluate(metrics)
                    val durationMs = System.currentTimeMillis() - sessionStartMs

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
                            transcriptionStatus = current.debugInfo.transcriptionStatus
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
                transcriptionJob?.cancelAndJoin()
                transcriptionJob = null
                localTranscriber.stop()
                audioCapture.stop()
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
        /** RMS threshold that classifies a frame as speech. Matches EnergyBasedVad default. */
        internal const val VAD_SPEECH_THRESHOLD = 300.0

        fun create(
            feedbackDispatcher: FeedbackDispatcher? = null,
            sessionRepository: SessionRepository? = null,
            feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
            localTranscriber: LocalTranscriber = NoOpLocalTranscriber()
        ): SpeechCoachSessionManager = SpeechCoachSessionManager(
            feedbackDispatcher = feedbackDispatcher,
            sessionRepository = sessionRepository,
            feedbackDecision = feedbackDecision,
            localTranscriber = localTranscriber
        )
    }

    private fun computeFrameRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toLong() * s
        return sqrt(sum / samples.size)
    }

    private suspend fun startTranscriptionCollector() {
        try {
            localTranscriber.start()
            transcriptionJob = managerScope.launch {
                launch {
                    localTranscriber.status.collect { status ->
                        _liveState.update { current ->
                            current.copy(debugInfo = current.debugInfo.copy(transcriptionStatus = status))
                        }
                    }
                }
                localTranscriber.updates.collect { update ->
                    val snapshot = transcriptWpmCalculator.onUpdate(update)
                    _liveState.update { current ->
                        current.copy(
                            transcriptText = snapshot.transcriptPreview,
                            transcriptRollingWpm = snapshot.rollingWpm.toFloat()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _liveState.update {
                it.copy(debugInfo = it.debugInfo.copy(transcriptionStatus = TranscriptionEngineStatus.Unavailable))
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
                    peakEstimatedWpm = stats.peakEstimatedWpm
                )
            )
        }
    }
}
