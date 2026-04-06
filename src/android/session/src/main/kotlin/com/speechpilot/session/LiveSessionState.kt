package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.segmentation.VadFrameClassification
import com.speechpilot.transcription.TranscriptionEngineStatus

/**
 * Lightweight debug snapshot of the speech pipeline's internal state.
 */
data class DebugPipelineInfo(
    val targetWpm: Double = 0.0,
    val lastDecisionReason: String = "—",
    val isInCooldown: Boolean = false,
    val activePaceSource: PaceSignalSource = PaceSignalSource.None,
    val paceSourceReason: String = "no-usable-signal",
    val fallbackActive: Boolean = false,
    val transcriptReadyForDecision: Boolean = false,
    val decisionWpm: Double = 0.0,
    val transcriptWpm: Double = 0.0,
    val heuristicWpm: Double = 0.0,
    val transcriptionStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled,
    val vadFrameRms: Double = 0.0,
    val vadThreshold: Double = 0.0,
    val vadFrameClassification: VadFrameClassification = VadFrameClassification.Silence,
    val isSegmentOpen: Boolean = false,
    val openSegmentFrameCount: Int = 0,
    val openSegmentSilenceFrameCount: Int = 0,
    val finalizedSegmentsCount: Int = 0,
    val remoteLifecycle: String = "idle",
    val remoteProvider: String? = null,
    val remoteChunksReceived: Int = 0,
    val remotePartialUpdates: Int = 0,
    val remoteFeedbackCount: Int = 0,
)

/**
 * Snapshot of the live session state exposed to the UI layer.
 */
data class LiveSessionState(
    val sessionState: SessionState = SessionState.Idle,
    val mode: SessionMode = SessionMode.Active,
    val isListening: Boolean = false,
    /**
     * True when the VAD is currently classifying incoming audio as speech.
     * Updated continuously at frame cadence (~100 ms). Resets to false during silence.
     * Use this to show a live "speaking now" indicator.
     */
    val isSpeechActive: Boolean = false,
    /**
     * True once speech has been detected at any point during this session.
     * Remains true for the rest of the session after first speech is seen.
     * Use this to show "speech detected this session".
     */
    val isSpeechDetected: Boolean = false,
    /**
     * Normalized microphone RMS level in the range [0.0, 1.0].
     * Updated continuously at frame cadence (~100 ms). Use to drive a live level meter.
     */
    val micLevel: Float = 0f,
    /** Most recent raw estimated WPM from the latest processed segment. Approximate proxy only. */
    val currentWpm: Float = 0f,
    /** EMA-smoothed estimated WPM across recent segments. Reduces per-segment noise. Approximate proxy only. */
    val smoothedWpm: Float = 0f,
    /** Typed transcript debug diagnostics for local recognizer visibility. */
    val transcriptDebug: TranscriptDebugState = TranscriptDebugState(),
    val latestFeedback: FeedbackEvent? = null,
    val alertActive: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val debugInfo: DebugPipelineInfo = DebugPipelineInfo()
)
