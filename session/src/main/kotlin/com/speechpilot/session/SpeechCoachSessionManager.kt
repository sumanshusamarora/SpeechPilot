package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
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
import com.speechpilot.vad.EnergyBasedVad
import kotlinx.coroutines.CancellationException
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

/**
 * Central coordinator for the speech coaching session lifecycle.
 *
 * Wires the audio capture → segmentation → pace → feedback pipeline
 * and exposes observable [liveState] for the UI layer.
 *
 * @param segmenter Speech segmenter used to convert audio frames into speech segments.
 *   Defaults to [VadSpeechSegmenter] with energy-based VAD. Swap to inject a stub in tests.
 * @param feedbackDispatcher Optional dispatcher that executes feedback events (e.g. vibration).
 *   If null, feedback events are evaluated and stored in live state but not dispatched externally.
 * @param sessionRepository Optional repository for persisting session summaries.
 *   If null, sessions are not persisted (safe to omit until Room wiring is complete).
 */
class SpeechCoachSessionManager(
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    private val segmenter: SpeechSegmenter = VadSpeechSegmenter(EnergyBasedVad()),
    private val paceEstimator: PaceEstimator = RollingWindowPaceEstimator(),
    private val rollingPaceWindow: RollingPaceWindow = RollingPaceWindow(),
    private val feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
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

    override suspend fun start(mode: SessionMode) {
        // Guard: ignore if already starting, active, or stopping.
        when (_state.value) {
            is SessionState.Starting, is SessionState.Active, is SessionState.Stopping -> return
            else -> Unit
        }
        _state.value = SessionState.Starting
        paceEstimator.reset()
        rollingPaceWindow.reset()

        pipelineJob = managerScope.launch {
            val sessionStartMs = System.currentTimeMillis()
            _state.value = SessionState.Active
            _liveState.update {
                it.copy(
                    sessionState = SessionState.Active,
                    mode = mode,
                    isListening = true,
                    stats = SessionStats(startedAtMs = sessionStartMs)
                )
            }
            audioCapture.start()

            try {
                segmenter.segment(audioCapture.frames()).collect { segment ->
                    val metrics = paceEstimator.estimate(segment)
                    rollingPaceWindow.update(metrics)
                    val feedback = feedbackDecision.evaluate(metrics)
                    val durationMs = System.currentTimeMillis() - sessionStartMs

                    // Dispatch to the external output channel (e.g. vibration) when a new
                    // feedback event was produced. Suppressed in Passive mode.
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
                        // alertActive tracks whether the most recent event was a coaching alert.
                        // It resets to false when pace returns to target; holds its last value
                        // when no new feedback event fires (cooldown / null).
                        val newAlertActive = when (feedback) {
                            FeedbackEvent.SlowDown, FeedbackEvent.SpeedUp -> true
                            FeedbackEvent.OnTarget -> false
                            null -> current.alertActive
                        }
                        current.copy(
                            isSpeechDetected = true,
                            currentWpm = metrics.estimatedWpm.toFloat(),
                            smoothedWpm = rollingPaceWindow.smoothedEstimatedWpm().toFloat(),
                            latestFeedback = feedback ?: current.latestFeedback,
                            alertActive = newAlertActive,
                            stats = newStats
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = SessionState.Error(e)
                _liveState.update { it.copy(isListening = false) }
            } finally {
                audioCapture.stop()
            }
        }
    }

    override suspend fun stop() {
        // Guard: ignore if already idle or in the process of stopping.
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
        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
        _state.value = SessionState.Idle
    }

    /** Releases the internal coroutine scope. Call when this manager is no longer needed. */
    fun release() {
        managerScope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /**
         * Creates a production-ready [SpeechCoachSessionManager] with default audio, VAD,
         * segmentation, and pace components wired internally.
         *
         * Callers in the UI layer only need to supply cross-cutting dependencies
         * ([feedbackDispatcher], [sessionRepository], [feedbackDecision]). All lower-level
         * module types (AudioCapture, SpeechSegmenter, PaceEstimator, RollingPaceWindow)
         * are resolved internally, keeping those types off the caller's module classpath.
         *
         * This prevents compile errors in modules (e.g. `:ui`) that depend on `:session`
         * but not on `:audio`, `:vad`, `:segmentation`, or `:pace` directly.
         */
        fun create(
            feedbackDispatcher: FeedbackDispatcher? = null,
            sessionRepository: SessionRepository? = null,
            feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision()
        ): SpeechCoachSessionManager = SpeechCoachSessionManager(
            feedbackDispatcher = feedbackDispatcher,
            sessionRepository = sessionRepository,
            feedbackDecision = feedbackDecision
        )
    }

    private fun persistSessionSummary(state: LiveSessionState) {
        val repo = sessionRepository ?: return
        val stats = state.stats
        if (stats.startedAtMs == 0L) return
        // Capture endedAtMs here (before launching) so it reflects when stop() was called,
        // not when the persistence coroutine happens to execute.
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
