package com.speechpilot.session

import com.speechpilot.transcription.TranscriptionBackend
import com.speechpilot.transcription.TranscriptionDiagnostics
import com.speechpilot.transcription.TranscriptionEngineStatus

/**
 * Typed transcript diagnostics exposed for UI/debug visibility.
 *
 * @param isChunkBased `true` when the active backend produces chunk-based (non-streaming)
 *   updates rather than continuous partial + final results. Currently `true` for the Whisper.cpp
 *   backend, `false` for Vosk and Android SpeechRecognizer. The UI uses this flag to show
 *   appropriate language (e.g. "Whisper processing…" rather than "Listening…").
 * @param lastChunkAtMs Timestamp of the last received transcript update. Combined with
 *   [isChunkBased], the UI can show a staleness hint when no update has arrived for a while.
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
    val lastUpdateAtMs: Long? = null,
    val isChunkBased: Boolean = false,
    val lastChunkAtMs: Long? = null,
    val diagnostics: TranscriptionDiagnostics = TranscriptionDiagnostics(),
)

enum class TranscriptDebugStatus {
    Disabled,
    Listening,
    WaitingForSpeech,
    PartialAvailable,
    FinalAvailable,
    ModelUnavailable,
    /** Whisper native library (`libwhisper_jni.so`) failed to load on this device. */
    NativeLibraryUnavailable,
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
        TranscriptionEngineStatus.NativeLibraryUnavailable -> TranscriptDebugStatus.NativeLibraryUnavailable
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
