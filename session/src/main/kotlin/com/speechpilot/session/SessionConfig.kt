package com.speechpilot.session

data class SessionConfig(
    val sampleRate: Int = 16_000,
    val targetWpm: Int = 130,
    val tolerancePct: Float = 0.15f,
    val feedbackCooldownMs: Long = 5_000L
)
