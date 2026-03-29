package com.speechpilot.data

data class SessionRecord(
    val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val averageWpm: Double,
    val durationMs: Long = endedAtMs - startedAtMs
)
