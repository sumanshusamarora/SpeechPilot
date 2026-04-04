package com.speechpilot.transcription

import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local transcription contract.
 *
 * Implementations expose [activeBackend] so the rest of the app can observe which backend is
 * actually running without depending on concrete types. The preferred backend is
 * [TranscriptionBackend.DedicatedLocalStt] (Vosk); [TranscriptionBackend.AndroidSpeechRecognizer]
 * is the fallback path.
 *
 * Backends that require PCM audio from the app pipeline (e.g. Vosk) accept the shared audio
 * source via [setAudioSource]. Backends that manage their own audio (e.g. Android
 * SpeechRecognizer) may ignore it — the default implementation is a no-op.
 * Call [setAudioSource] before [start].
 */
interface LocalTranscriber {
    val updates: Flow<TranscriptUpdate>
    val status: StateFlow<TranscriptionEngineStatus>
    val diagnostics: StateFlow<TranscriptionDiagnostics>
    /** Identifies which backend is currently active. Updated on [start] and [stop]. */
    val activeBackend: StateFlow<TranscriptionBackend>

    /**
     * Provides the shared audio frame source for backends that read PCM from the app pipeline
     * rather than opening their own [android.media.AudioRecord].
     * Must be called before [start]. Default implementation is a no-op.
     */
    fun setAudioSource(frames: Flow<AudioFrame>) {}

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
