package com.speechpilot.session

import com.speechpilot.feedback.FeedbackEvent

data class LiveSessionState(
    val sessionState: SessionState = SessionState.Idle,
    val isListening: Boolean = false,
    val currentWpm: Float = 0f,
    val latestFeedback: FeedbackEvent? = null,
    val stats: SessionStats = SessionStats()
)
