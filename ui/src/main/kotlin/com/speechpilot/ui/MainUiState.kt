package com.speechpilot.ui

import com.speechpilot.feedback.FeedbackEvent

data class MainUiState(
    val statusText: String = "Ready",
    val isSessionActive: Boolean = false,
    val isListening: Boolean = false,
    val currentWpm: Float = 0f,
    val latestFeedback: FeedbackEvent? = null,
    val permissionGranted: Boolean = false
)
