package com.speechpilot.transcription

/**
 * Runtime status surfaced for debug visibility.
 */
enum class TranscriptionEngineStatus {
    Disabled,
    Listening,
    Restarting,
    Unavailable,
    Error
}
