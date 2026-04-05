package com.speechpilot.settings

data class UserPreferences(
    val targetWpm: Int = 130,
    val tolerancePct: Float = 0.15f,
    val feedbackCooldownMs: Long = 5_000L,
    val micSampleRate: Int = 16_000,
    /** Local on-device transcription. Enabled by default — provides transcript text and text-derived WPM. */
    val transcriptionEnabled: Boolean = true,
    /**
     * When `true`, Whisper.cpp is used as the primary STT backend instead of Vosk.
     * Defaults to `true` so fresh installs provision the lighter Whisper tiny.en model.
     * Has no effect when [transcriptionEnabled] is `false`.
     */
    val preferWhisperBackend: Boolean = true,
)
