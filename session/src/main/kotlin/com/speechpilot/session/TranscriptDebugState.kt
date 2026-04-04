package com.speechpilot.session

import com.speechpilot.transcription.TranscriptionBackend
import com.speechpilot.transcription.TranscriptionEngineStatus

/**
 * Typed transcript diagnostics exposed for UI/debug visibility.
 */
data class TranscriptDebugState(
    val debugEnabled: Boolean = false,
    val status: TranscriptDebugStatus = TranscriptDebugStatus.Disabled,
    val engineStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled,
    val activeBackend: TranscriptionBackend = TranscriptionBackend.None,
    val transcriptText: String = "",
    val partialTranscriptPresent: Boolean = false,
    val finalizedWordCount: Int = 0,
    val rollingWordCount: Int = 0,
    val rollingWpm: Float = 0f,
    val wpmPendingFinalRecognition: Boolean = false,
    val lastUpdateAtMs: Long? = null
)

enum class TranscriptDebugStatus {
    Disabled,
    Listening,
    WaitingForSpeech,
    PartialAvailable,
    FinalAvailable,
    ModelUnavailable,
    Unavailable,
    Error
}

internal fun resolveTranscriptDebugStatus(
    debugEnabled: Boolean,
    engineStatus: TranscriptionEngineStatus,
    isSessionListening: Boolean,
    partialTranscriptPresent: Boolean,
    finalizedWordCount: Int
): TranscriptDebugStatus {
    if (!debugEnabled) return TranscriptDebugStatus.Disabled
    return when (engineStatus) {
        TranscriptionEngineStatus.Unavailable -> TranscriptDebugStatus.Unavailable
        TranscriptionEngineStatus.ModelUnavailable -> TranscriptDebugStatus.ModelUnavailable
        TranscriptionEngineStatus.Error -> TranscriptDebugStatus.Error
        TranscriptionEngineStatus.Disabled -> TranscriptDebugStatus.Disabled
        TranscriptionEngineStatus.InitializingModel -> TranscriptDebugStatus.Listening
        TranscriptionEngineStatus.Restarting -> {
            if (finalizedWordCount > 0) TranscriptDebugStatus.FinalAvailable
            else if (partialTranscriptPresent) TranscriptDebugStatus.PartialAvailable
            else TranscriptDebugStatus.Listening
        }
        TranscriptionEngineStatus.Listening -> {
            if (finalizedWordCount > 0) TranscriptDebugStatus.FinalAvailable
            else if (partialTranscriptPresent) TranscriptDebugStatus.PartialAvailable
            else if (isSessionListening) TranscriptDebugStatus.WaitingForSpeech
            else TranscriptDebugStatus.Listening
        }
    }
}
