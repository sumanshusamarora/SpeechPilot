package com.speechpilot.transcription

/**
 * Which transcription backend is currently active.
 *
 * The preferred path is [DedicatedLocalStt] (Vosk-based on-device recognition).
 * [AndroidSpeechRecognizer] is the fallback when the dedicated backend is unavailable.
 */
enum class TranscriptionBackend {
    /**
     * Vosk on-device STT — the primary preferred backend.
     * Requires model assets to be present in the app's files directory.
     * Provides deterministic offline recognition with no cloud dependency.
     */
    DedicatedLocalStt,

    /**
     * Android [android.speech.SpeechRecognizer] — fallback / compatibility mode.
     * Recognition quality and offline availability are device- and service-dependent.
     * Used when the dedicated backend is unavailable or model assets are missing.
     */
    AndroidSpeechRecognizer,

    /** No transcription backend is active (transcription disabled or session not started). */
    None
}
