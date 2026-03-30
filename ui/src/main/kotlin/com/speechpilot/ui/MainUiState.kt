package com.speechpilot.ui

import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.session.DebugPipelineInfo
import com.speechpilot.session.SessionMode
import com.speechpilot.session.TranscriptDebugState

data class MainUiState(
    val statusText: String = "Ready",
    val isSessionActive: Boolean = false,
    val isListening: Boolean = false,
    /** True while the VAD currently classifies audio as speech (live, frame-cadence). */
    val isSpeechActive: Boolean = false,
    /** True once speech has been detected at any point during this session. */
    val isSpeechDetected: Boolean = false,
    /** Normalized microphone RMS level [0, 1]. Updated at frame cadence during sessions. */
    val micLevel: Float = 0f,
    /** Most recent raw estimated WPM. Approximate proxy — not exact WPM. */
    val currentWpm: Float = 0f,
    /** EMA-smoothed estimated WPM. Approximate proxy — not exact WPM. */
    val smoothedWpm: Float = 0f,
    /** Mirrors the settings toggle for local transcript debug visibility. */
    val transcriptDebugEnabled: Boolean = false,
    /** Typed runtime transcript debug diagnostics from the session pipeline. */
    val transcriptDebug: TranscriptDebugState = TranscriptDebugState(),
    val segmentCount: Int = 0,
    val latestFeedback: FeedbackEvent? = null,
    /**
     * True when the most recent feedback event was a coaching alert (SlowDown or SpeedUp).
     * Resets to false when pace returns to target.
     */
    val alertActive: Boolean = false,
    val permissionGranted: Boolean = false,
    /** Non-null when the session has encountered a runtime error. Cleared on next session start. */
    val errorMessage: String? = null,
    /** Operational mode of the current or most recently completed session. */
    val sessionMode: SessionMode = SessionMode.Active,
    /** Live debug snapshot for pipeline calibration. Populated during an active session. */
    val debugInfo: DebugPipelineInfo = DebugPipelineInfo(),
    /** True when the current (or most recently started) session is analyzing an uploaded file. */
    val isFileSession: Boolean = false,
    /**
     * Content URI string of the file being analyzed, or null for live-microphone sessions.
     * Non-null only when [isFileSession] is true.
     */
    val fileSessionUri: String? = null
)
