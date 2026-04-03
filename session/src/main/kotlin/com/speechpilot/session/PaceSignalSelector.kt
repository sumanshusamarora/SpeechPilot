package com.speechpilot.session

import com.speechpilot.pace.PaceMetrics

enum class PaceSignalSource {
    Transcript,
    Heuristic,
    None
}

data class PaceSignalSelection(
    val source: PaceSignalSource,
    val selectedWpm: Double,
    val reason: String,
    val transcriptReady: Boolean,
    val fallbackActive: Boolean
)

const val MIN_TRANSCRIPT_WORDS_FOR_DECISION = 3

fun selectPaceSignal(
    transcriptEnabled: Boolean,
    transcriptStatus: TranscriptDebugStatus,
    transcriptWpm: Double,
    transcriptFinalizedWordCount: Int,
    transcriptRollingWordCount: Int,
    heuristicWpm: Double
): PaceSignalSelection {
    val heuristicAvailable = heuristicWpm > 0.0
    if (!transcriptEnabled) {
        return if (heuristicAvailable) {
            PaceSignalSelection(
                source = PaceSignalSource.Heuristic,
                selectedWpm = heuristicWpm,
                reason = "transcript-disabled",
                transcriptReady = false,
                fallbackActive = false
            )
        } else {
            PaceSignalSelection(
                source = PaceSignalSource.None,
                selectedWpm = 0.0,
                reason = "no-usable-signal",
                transcriptReady = false,
                fallbackActive = false
            )
        }
    }

    if (transcriptStatus == TranscriptDebugStatus.Unavailable ||
        transcriptStatus == TranscriptDebugStatus.ModelUnavailable) {
        return if (heuristicAvailable) {
            PaceSignalSelection(
                source = PaceSignalSource.Heuristic,
                selectedWpm = heuristicWpm,
                reason = "transcript-unavailable-fallback",
                transcriptReady = false,
                fallbackActive = true
            )
        } else {
            PaceSignalSelection(
                source = PaceSignalSource.None,
                selectedWpm = 0.0,
                reason = "transcript-unavailable-no-fallback",
                transcriptReady = false,
                fallbackActive = false
            )
        }
    }

    if (transcriptStatus == TranscriptDebugStatus.Error) {
        return if (heuristicAvailable) {
            PaceSignalSelection(
                source = PaceSignalSource.Heuristic,
                selectedWpm = heuristicWpm,
                reason = "transcript-error-fallback",
                transcriptReady = false,
                fallbackActive = true
            )
        } else {
            PaceSignalSelection(
                source = PaceSignalSource.None,
                selectedWpm = 0.0,
                reason = "transcript-error-no-fallback",
                transcriptReady = false,
                fallbackActive = false
            )
        }
    }

    val transcriptReady = transcriptFinalizedWordCount > 0 &&
        transcriptRollingWordCount >= MIN_TRANSCRIPT_WORDS_FOR_DECISION &&
        transcriptWpm > 0.0 &&
        transcriptStatus == TranscriptDebugStatus.FinalAvailable

    if (transcriptReady) {
        return PaceSignalSelection(
            source = PaceSignalSource.Transcript,
            selectedWpm = transcriptWpm,
            reason = "transcript-ready",
            transcriptReady = true,
            fallbackActive = false
        )
    }

    return if (heuristicAvailable) {
        PaceSignalSelection(
            source = PaceSignalSource.Heuristic,
            selectedWpm = heuristicWpm,
            reason = "transcript-pending-fallback",
            transcriptReady = false,
            fallbackActive = true
        )
    } else {
        PaceSignalSelection(
            source = PaceSignalSource.None,
            selectedWpm = 0.0,
            reason = "transcript-pending-no-fallback",
            transcriptReady = false,
            fallbackActive = false
        )
    }
}

fun asDecisionMetrics(selection: PaceSignalSelection, windowDurationMs: Long): PaceMetrics =
    PaceMetrics(
        estimatedWpm = selection.selectedWpm,
        windowDurationMs = windowDurationMs
    )
