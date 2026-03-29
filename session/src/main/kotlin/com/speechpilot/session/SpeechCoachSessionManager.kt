package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.MicrophoneCapture
import com.speechpilot.feedback.FeedbackDecision
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.pace.PaceEstimator
import com.speechpilot.pace.RollingWindowPaceEstimator
import com.speechpilot.segmentation.SpeechSegmenter
import com.speechpilot.segmentation.VadSpeechSegmenter
import com.speechpilot.vad.EnergyBasedVad
import com.speechpilot.vad.VoiceActivityDetector
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
 * Wires the audio capture → VAD → segmentation → pace → feedback pipeline
 * and exposes observable [liveState] for the UI layer.
 */
class SpeechCoachSessionManager(
    private val audioCapture: AudioCapture = MicrophoneCapture(),
    vad: VoiceActivityDetector = EnergyBasedVad(),
    private val paceEstimator: PaceEstimator = RollingWindowPaceEstimator(),
    private val feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : SessionManager {

    private val segmenter: SpeechSegmenter = VadSpeechSegmenter(vad)

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

        pipelineJob = managerScope.launch {
            val sessionStartMs = System.currentTimeMillis()
            _state.value = SessionState.Active
            _liveState.update { it.copy(sessionState = SessionState.Active, isListening = true) }
            audioCapture.start()

            try {
                segmenter.segment(audioCapture.frames()).collect { segment ->
                    val metrics = paceEstimator.estimate(segment)
                    val feedback = feedbackDecision.evaluate(metrics)
                    val durationMs = System.currentTimeMillis() - sessionStartMs
                    _liveState.update { current ->
                        current.copy(
                            currentWpm = metrics.wordsPerMinute.toFloat(),
                            latestFeedback = feedback ?: current.latestFeedback,
                            stats = current.stats.copy(
                                segmentCount = current.stats.segmentCount + 1,
                                durationMs = durationMs
                            )
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
        paceEstimator.reset()
        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
        _state.value = SessionState.Idle
    }

    /** Releases the internal coroutine scope. Call when this manager is no longer needed. */
    fun release() {
        managerScope.coroutineContext[Job]?.cancel()
    }
}
