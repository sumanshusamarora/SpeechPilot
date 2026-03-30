package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent

/**
 * Lightweight debug snapshot of the speech pipeline's internal state.
 */
data class DebugPipelineInfo(
    val targetWpm: Double = 0.0,
    val lastDecisionReason: String = "—",
    val isInCooldown: Boolean = false,
    val transcriptionStatus: String = "disabled"
)

/**
 * Snapshot of the live session state exposed to the UI layer.
 */
data class LiveSessionState(
    val sessionState: SessionState = SessionState.Idle,
    val mode: SessionMode = SessionMode.Active,
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
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
