package com.speechpilot.transcription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local transcription contract.
 *
 * Implementations expose [activeBackend] so the rest of the app can observe which backend is
 * actually running without depending on concrete types. The preferred backend is
 * [TranscriptionBackend.DedicatedLocalStt] (Vosk); [TranscriptionBackend.AndroidSpeechRecognizer]
 * is the fallback path.
 */
interface LocalTranscriber {
    val updates: Flow<TranscriptUpdate>
    val status: StateFlow<TranscriptionEngineStatus>
    /** Identifies which backend is currently active. Updated on [start] and [stop]. */
    val activeBackend: StateFlow<TranscriptionBackend>
    suspend fun start()
    suspend fun stop()
}

enum class TranscriptStability {
    Partial,
    Final
}

data class TranscriptUpdate(
    val text: String,
    val stability: TranscriptStability,
    val receivedAtMs: Long
)
