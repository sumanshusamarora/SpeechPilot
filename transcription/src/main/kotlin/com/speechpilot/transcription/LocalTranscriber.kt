package com.speechpilot.transcription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local transcription contract.
 *
 * Implementations may rely on device speech services. Callers should treat [status] as best-effort
 * runtime state and not assume hard offline guarantees across all devices.
 */
interface LocalTranscriber {
    val updates: Flow<TranscriptUpdate>
    val status: StateFlow<TranscriptionEngineStatus>
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
