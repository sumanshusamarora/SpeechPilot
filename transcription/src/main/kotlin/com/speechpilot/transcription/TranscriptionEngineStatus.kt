package com.speechpilot.transcription

/**
 * Runtime status for the active transcription engine.
 *
 * These states cover the full lifecycle of both the dedicated Vosk backend and the
 * Android SpeechRecognizer fallback.
 */
enum class TranscriptionEngineStatus {
    /** Transcription is not running (not enabled, or session not started). */
    Disabled,
    /** Dedicated backend is loading its model. Recognition has not started yet. */
    InitializingModel,
    /** Dedicated backend could not start: model assets were not found on the device. */
    ModelUnavailable,
    /** Engine is actively listening and may produce transcript updates. */
    Listening,
    /** Engine is restarting (e.g. SpeechRecognizer result boundary — auto-restart in progress). */
    Restarting,
    /** Recognition service is not available on this device. */
    Unavailable,
    /** Engine encountered a recognition error. */
    Error
}
