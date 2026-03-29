package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.MicrophoneCapture
import com.speechpilot.data.SessionRecord
import com.speechpilot.data.SessionRepository
import com.speechpilot.feedback.FeedbackDecision
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
 * @param sessionRepository Optional repository for persisting session summaries.
 *   If null, sessions are not persisted (safe to omit until Room wiring is complete).
 */
class SpeechCoachSessionManager(
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    private val segmenter: SpeechSegmenter = VadSpeechSegmenter(EnergyBasedVad()),
    private val paceEstimator: PaceEstimator = RollingWindowPaceEstimator(),
    private val rollingPaceWindow: RollingPaceWindow = RollingPaceWindow(),
    private val feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
    private val sessionRepository: SessionRepository? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveSessionState())
    override val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var pipelineJob: Job? = null

    override suspend fun start() {
        if (_state.value != SessionState.Idle) return
        _state.value = SessionState.Starting
        paceEstimator.reset()
        rollingPaceWindow.reset()

        pipelineJob = managerScope.launch {
            val sessionStartMs = System.currentTimeMillis()
            _state.value = SessionState.Active
            _liveState.update {
                it.copy(
                    sessionState = SessionState.Active,
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

                    _liveState.update { current ->
                        val newStats = current.stats.copy(
                            durationMs = durationMs,
                            totalSpeechActiveDurationMs =
                                current.stats.totalSpeechActiveDurationMs + segment.durationMs,
                            segmentCount = current.stats.segmentCount + 1,
                            averageEstimatedWpm = rollingPaceWindow.averageEstimatedWpm(),
                            peakEstimatedWpm = rollingPaceWindow.peakEstimatedWpm()
                        )
                        current.copy(
                            isSpeechDetected = true,
                            currentWpm = metrics.estimatedWpm.toFloat(),
                            smoothedWpm = rollingPaceWindow.smoothedEstimatedWpm().toFloat(),
                            latestFeedback = feedback ?: current.latestFeedback,
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
        if (_state.value == SessionState.Idle) return
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
