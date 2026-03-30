package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent

/**
 * Lightweight debug snapshot of the speech pipeline's internal state.
 *
 * Populated by [SpeechCoachSessionManager] after each segment is processed and exposed
 * via [LiveSessionState.debugInfo]. Intended to aid real-device calibration and development.
 * All fields default to their zero/empty values before any segments are processed.
 *
 * @param targetWpm The configured target pace threshold (in estimated WPM).
 * @param lastDecisionReason Human-readable label for the outcome of the most recent
 *   [com.speechpilot.feedback.FeedbackDecision.evaluate] call (e.g. "on-target",
 *   "speed-up", "cooldown-suppressed", "invalid-signal").
 * @param isInCooldown True when the feedback cooldown window is currently active
 *   and repeat alerts are suppressed.
 */
data class DebugPipelineInfo(
    val targetWpm: Double = 0.0,
    val lastDecisionReason: String = "—",
    val isInCooldown: Boolean = false
)

/**
 * Snapshot of the live session state exposed to the UI layer.
 *
 * The WPM-related fields ([currentWpm], [smoothedWpm]) reflect the approximate pace proxy
 * described in [com.speechpilot.pace.PaceMetrics] — they are not exact words-per-minute values.
 */
data class LiveSessionState(
    val sessionState: SessionState = SessionState.Idle,
    /** Operational mode of the current (or most recent) session. */
    val mode: SessionMode = SessionMode.Active,
    /** True while the audio pipeline is active and frames are being captured. */
    val isListening: Boolean = false,
    /** True once at least one speech segment has been detected in the current session. */
    val isSpeechDetected: Boolean = false,
    /** Most recent raw estimated WPM from the latest processed segment. Approximate proxy only. */
    val currentWpm: Float = 0f,
    /** EMA-smoothed estimated WPM across recent segments. Reduces per-segment noise. Approximate proxy only. */
    val smoothedWpm: Float = 0f,
    val latestFeedback: FeedbackEvent? = null,
    /**
     * True when the most recent feedback event was an alert (SlowDown or SpeedUp).
     * Resets to false when pace returns to target or on session stop.
     *
     * Intended for UI emphasis and is only as precise as the feedback cooldown allows.
     */
    val alertActive: Boolean = false,
    val stats: SessionStats = SessionStats(),
    /**
     * Lightweight debug snapshot populated after each segment.
     * Exposes internal pipeline state for real-device calibration and development diagnostics.
     */
    val debugInfo: DebugPipelineInfo = DebugPipelineInfo()
)
