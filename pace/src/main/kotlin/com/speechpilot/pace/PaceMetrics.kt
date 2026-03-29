package com.speechpilot.pace

data class PaceMetrics(
    val wordsPerMinute: Double,
    val syllablesPerSecond: Double,
    val windowDurationMs: Long
)
