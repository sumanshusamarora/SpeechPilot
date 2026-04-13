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
    /**
     * Whisper native library (`libwhisper_jni.so`) was not loaded by the runtime.
     *
     * This is distinct from [ModelUnavailable]: the model file may be present, but the native
     * library failed to load (e.g. ABI mismatch, missing from APK, unit-test build without
     * native compilation). [RoutingLocalTranscriber] treats this as an unrecoverable backend
     * failure and activates the Android SpeechRecognizer fallback.
     */
    NativeLibraryUnavailable,
    /** Engine is actively listening and may produce transcript updates. */
    Listening,
    /** Engine is restarting (e.g. SpeechRecognizer result boundary — auto-restart in progress). */
    Restarting,
    /** Recognition service is not available on this device. */
    Unavailable,
    /** Engine encountered a recognition error. */
    Error
}
