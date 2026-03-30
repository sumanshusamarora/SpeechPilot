package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.transcription.TranscriptionEngineStatus

/**
 * Lightweight debug snapshot of the speech pipeline's internal state.
 */
data class DebugPipelineInfo(
    val targetWpm: Double = 0.0,
    val lastDecisionReason: String = "—",
    val isInCooldown: Boolean = false,
    val transcriptionStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled
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
    /** Rolling transcript text shown for debug calibration. */
    val transcriptText: String = "",
    /** Transcript-derived rolling WPM from finalized recognized words. */
    val transcriptRollingWpm: Float = 0f,
    val latestFeedback: FeedbackEvent? = null,
    val alertActive: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val debugInfo: DebugPipelineInfo = DebugPipelineInfo()
)
