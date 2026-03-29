package com.speechpilot.pace

/**
 * Pace metrics derived from a rolling window of speech segments.
 *
 * **IMPORTANT – approximate signal only:** [estimatedWpm] is a proxy measure derived
 * from segment duration and count, not from word-boundary detection or speech recognition.
 * Phase 1 cannot measure true words per minute without STT or syllable detection.
 * Use this value as a relative pace indicator, not an absolute WPM reading.
 *
 * @param estimatedWpm Approximate pace proxy expressed in estimated words per minute.
 * @param windowDurationMs Total speech duration (ms) covered by the window used to compute this estimate.
 */
data class PaceMetrics(
    val estimatedWpm: Double,
    val windowDurationMs: Long
)
