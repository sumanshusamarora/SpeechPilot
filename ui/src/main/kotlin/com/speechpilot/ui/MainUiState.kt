package com.speechpilot.ui

import com.speechpilot.feedback.FeedbackEvent
import com.speechpilot.modelmanager.ModelInstallState
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
    /** True when on-device transcription is enabled (user setting, default on). */
    val transcriptionEnabled: Boolean = false,
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
    val fileSessionUri: String? = null,
    /**
     * Install state of the model required for the currently active STT backend.
     *
     * - When Vosk is selected: reflects the Vosk model install state.
     * - When Whisper is selected: reflects the Whisper model install state.
     * - Null when transcription is disabled.
     *
     * The UI shows download/install progress and a retry button based on this state.
     * Only the active backend's model is provisioned and observed — the inactive backend's
     * model is not downloaded eagerly.
     */
    val activeModelInstallState: ModelInstallState? = null,
    /** Display name of the active backend's required model (e.g. "Vosk small (en-US)"). */
    val activeModelDisplayName: String = "Speech Model",
    /** Approximate download size in MB. Shown in the provisioning card before download starts. */
    val activeModelApproxSizeMb: Int = 0,
    /** Whether the download is large enough to suggest a Wi-Fi connection. */
    val activeModelWifiRecommended: Boolean = false,
)
