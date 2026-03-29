package com.speechpilot.settings

data class UserPreferences(
    val targetWpm: Int = 130,
    val tolerancePct: Float = 0.15f,
    val feedbackCooldownMs: Long = 5_000L,
    val micSampleRate: Int = 16_000
)
