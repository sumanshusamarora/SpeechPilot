package com.speechpilot.settings

data class UserPreferences(
    val targetWpm: Int = 130,
    val tolerancePct: Float = 0.15f,
    val feedbackCooldownMs: Long = 5_000L,
    val micSampleRate: Int = 16_000,
    /** Debug-only local transcript mode. Disabled by default due runtime overhead. */
    val localTranscriptDebugEnabled: Boolean = false
)
