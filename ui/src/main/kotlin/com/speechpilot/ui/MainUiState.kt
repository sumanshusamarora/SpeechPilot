package com.speechpilot.ui

import com.speechpilot.feedback.FeedbackEvent

data class MainUiState(
    val statusText: String = "Ready",
    val isSessionActive: Boolean = false,
    val isListening: Boolean = false,
    val isSpeechDetected: Boolean = false,
    /** Most recent raw estimated WPM. Approximate proxy — not exact WPM. */
    val currentWpm: Float = 0f,
    /** EMA-smoothed estimated WPM. Approximate proxy — not exact WPM. */
    val smoothedWpm: Float = 0f,
    val segmentCount: Int = 0,
    val latestFeedback: FeedbackEvent? = null,
    /**
     * True when the most recent feedback event was a coaching alert (SlowDown or SpeedUp).
     * Resets to false when pace returns to target.
     */
    val alertActive: Boolean = false,
    val permissionGranted: Boolean = false
)
