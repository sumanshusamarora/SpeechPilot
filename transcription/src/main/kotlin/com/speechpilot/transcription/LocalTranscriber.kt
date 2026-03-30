package com.speechpilot.transcription

import kotlinx.coroutines.flow.Flow

/**
 * Local-only transcription contract.
 *
 * Implementations are expected to run fully on-device and emit incremental updates.
 * Partial hypotheses can be delayed, revised, or dropped by the recognizer.
 */
interface LocalTranscriber {
    val updates: Flow<TranscriptUpdate>
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
