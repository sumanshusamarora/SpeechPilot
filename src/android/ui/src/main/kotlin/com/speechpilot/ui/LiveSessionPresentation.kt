package com.speechpilot.ui

import com.speechpilot.session.TranscriptDebugStatus
import com.speechpilot.session.PaceSignalSource
import com.speechpilot.transcription.TranscriptionBackend

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
    if (!state.transcriptionEnabled) {
        return TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "Enable local transcription or switch live sessions to the realtime backend in Settings."
        )
    }

    val transcript = state.transcriptDebug
    val diagnostics = transcript.diagnostics
    return when (transcript.status) {
        TranscriptDebugStatus.Disabled -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = "Transcript mode is currently disabled."
        )

        TranscriptDebugStatus.Unavailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = diagnostics.lastTranscriptError?.message
                ?: "This device/runtime does not currently provide local recognition."
        )

        TranscriptDebugStatus.ModelUnavailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "${backendLabel(diagnostics.selectedBackend)} model unavailable",
            bodyText = diagnostics.fallbackReason?.message
                ?: buildString {
                    append("Model file not found")
                    diagnostics.modelPath?.let {
                        append(" at ")
                        append(it)
                    }
                    if (diagnostics.fallbackActive) {
                        append(". Android SpeechRecognizer fallback is active.")
                    }
                }
        )

        TranscriptDebugStatus.NativeLibraryUnavailable -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Whisper runtime unavailable — using Android fallback",
            bodyText = diagnostics.fallbackReason?.message
                ?: buildString {
                    append("The Whisper native library (")
                    append(diagnostics.nativeLibraryName ?: "libwhisper_jni.so")
                    append(") could not be loaded")
                    diagnostics.nativeLibraryLoadError?.let {
                        append(": ")
                        append(it)
                    }
                    append(". Android SpeechRecognizer fallback is active.")
                }
        )

        TranscriptDebugStatus.Error -> TranscriptSurfacePresentation(
            title = "Transcript",
            helperText = "Transcription unavailable",
            bodyText = diagnostics.lastTranscriptError?.message
                ?: diagnostics.fallbackReason?.message
                ?: "Recognizer error. SpeechPilot will retry while the session is active."
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

private fun backendLabel(backend: TranscriptionBackend): String = when (backend) {
    TranscriptionBackend.RemoteRealtime -> "Realtime backend"
    TranscriptionBackend.DedicatedLocalStt -> "Vosk"
    TranscriptionBackend.WhisperCpp -> "Whisper"
    TranscriptionBackend.AndroidSpeechRecognizer -> "Android recognizer"
    TranscriptionBackend.None -> "No backend"
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
