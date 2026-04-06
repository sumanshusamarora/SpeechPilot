package com.speechpilot.transcription

/**
 * Which transcription backend is currently active.
 *
 * The preferred dedicated local path is either [VoskStt] or [WhisperCpp] depending on the user's
 * selected STT backend. [AndroidSpeechRecognizer] is the fallback when the dedicated backend is
 * unavailable.
 *
 * Legacy note: the original [DedicatedLocalStt] value is kept for backward compatibility and
 * continues to be reported by [VoskLocalTranscriber]. New code should check for [WhisperCpp]
 * explicitly when Whisper is the active backend.
 */
enum class TranscriptionBackend {
    /** Remote backend over the SpeechPilot realtime websocket protocol. */
    RemoteRealtime,

    /**
     * Vosk on-device STT — a primary preferred backend.
     * Requires model assets to be present in the app's files directory.
     * Provides deterministic offline recognition with no cloud dependency.
     */
    DedicatedLocalStt,

    /**
     * Whisper.cpp on-device STT — alternative preferred backend.
     * Requires the ggml model file and the Whisper native library.
     * Chunk-based inference with no partial-result streaming; emits Final updates only.
     * May produce better transcript quality for accented English (e.g. Indian English).
     */
    WhisperCpp,

    /**
     * Android [android.speech.SpeechRecognizer] — fallback / compatibility mode.
     * Recognition quality and offline availability are device- and service-dependent.
     * Used when the dedicated backend is unavailable or model assets are missing.
     */
    AndroidSpeechRecognizer,

    /** No transcription backend is active (transcription disabled or session not started). */
    None
}
