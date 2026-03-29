package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent

/**
 * Snapshot of the live session state exposed to the UI layer.
 *
 * The WPM-related fields ([currentWpm], [smoothedWpm]) reflect the approximate pace proxy
 * described in [com.speechpilot.pace.PaceMetrics] — they are not exact words-per-minute values.
 */
data class LiveSessionState(
    val sessionState: SessionState = SessionState.Idle,
    /** True while the audio pipeline is active and frames are being captured. */
    val isListening: Boolean = false,
    /** True once at least one speech segment has been detected in the current session. */
    val isSpeechDetected: Boolean = false,
    /** Most recent raw estimated WPM from the latest processed segment. Approximate proxy only. */
    val currentWpm: Float = 0f,
    /** EMA-smoothed estimated WPM across recent segments. Reduces per-segment noise. Approximate proxy only. */
    val smoothedWpm: Float = 0f,
    val latestFeedback: FeedbackEvent? = null,
    val stats: SessionStats = SessionStats()
)
