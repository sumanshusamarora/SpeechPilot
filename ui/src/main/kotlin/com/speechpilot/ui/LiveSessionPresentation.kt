package com.speechpilot.ui

import com.speechpilot.session.TranscriptDebugStatus
import com.speechpilot.session.PaceSignalSource

data class TranscriptSurfacePresentation(
    val title: String,
    val helperText: String,
    val bodyText: String?,
    val showAsPartial: Boolean = false,
    val showAsFinal: Boolean = false
)

data class PaceMetricPresentation(
    val headline: String,
    val primaryValue: String,
    val primaryLabel: String,
    val detail: String,
    val usesTranscriptAsPrimary: Boolean
)

internal fun resolveTranscriptSurfacePresentation(state: MainUiState): TranscriptSurfacePresentation {
    if (!state.transcriptDebugEnabled) {
        return TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "Enable Local transcript debug in Settings to view on-device transcript text."
        )
    }

    val transcript = state.transcriptDebug
    return when (transcript.status) {
        TranscriptDebugStatus.Disabled -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "Transcript mode is currently disabled."
        )

        TranscriptDebugStatus.Unavailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "This device/runtime does not currently provide local recognition."
        )

        TranscriptDebugStatus.ModelUnavailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Dedicated STT model not installed",
            bodyText = "Vosk model assets are required for on-device transcription. " +
                "Place model files in the app's files directory to enable the dedicated backend. " +
                "Using Android SpeechRecognizer as fallback."
        )

        TranscriptDebugStatus.Error -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "Recognizer error. SpeechPilot will retry while the session is active."
        )

        TranscriptDebugStatus.PartialAvailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Partial transcript available",
            bodyText = transcript.transcriptText.ifBlank { "Listening for final words…" },
            showAsPartial = true
        )

        TranscriptDebugStatus.FinalAvailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Final transcript available",
            bodyText = transcript.transcriptText.ifBlank { "Final words recognized." },
            showAsFinal = true
        )

        TranscriptDebugStatus.WaitingForSpeech -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Listening for speech",
            bodyText = "No final words yet."
        )

        TranscriptDebugStatus.Listening -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Listening for speech",
            bodyText = "No final words yet."
        )
    }
}

internal fun resolvePaceMetricPresentation(state: MainUiState): PaceMetricPresentation {
    val debug = state.debugInfo
    return when (debug.activePaceSource) {
        PaceSignalSource.Transcript -> PaceMetricPresentation(
            headline = "Live Pace",
            primaryValue = "%.0f WPM".format(debug.decisionWpm),
            primaryLabel = "Text pace (transcript-derived)",
            detail = if (debug.heuristicWpm > 0.0) {
                "Heuristic pace: %.0f est-WPM".format(debug.heuristicWpm)
            } else {
                "Heuristic pace not available yet."
            },
            usesTranscriptAsPrimary = true
        )

        PaceSignalSource.Heuristic -> PaceMetricPresentation(
            headline = "Live Pace",
            primaryValue = if (debug.decisionWpm > 0.0) {
                "%.0f est-WPM".format(debug.decisionWpm)
            } else {
                "—"
            },
            primaryLabel = "Heuristic pace (estimated)",
            detail = when (debug.paceSourceReason) {
                "transcript-unavailable-fallback" ->
                    "Transcript unavailable — fallback active."
                "transcript-error-fallback" ->
                    "Transcript error — fallback active."
                "transcript-pending-fallback" ->
                    "Transcript pending — fallback active."
                else -> "Transcript mode disabled; using heuristic pace."
            },
            usesTranscriptAsPrimary = false
        )

        PaceSignalSource.None -> PaceMetricPresentation(
            headline = "Live Pace",
            primaryValue = "—",
            primaryLabel = "No usable pace signal yet",
            detail = "Waiting for transcript or heuristic pace input.",
            usesTranscriptAsPrimary = false
        )
    }
}
