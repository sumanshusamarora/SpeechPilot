package com.speechpilot.feedback

import com.speechpilot.pace.PaceMetrics

class ThresholdFeedbackDecision(
    private val targetWpm: Double = TARGET_WPM,
    private val tolerancePct: Double = TOLERANCE_PCT,
    private val cooldownMs: Long = COOLDOWN_MS
) : FeedbackDecision {

    private var lastFeedbackMs: Long = 0L

    override fun evaluate(metrics: PaceMetrics): FeedbackEvent? {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFeedbackMs < cooldownMs) return null

        val wpm = metrics.wordsPerMinute
        val lower = targetWpm * (1 - tolerancePct)
        val upper = targetWpm * (1 + tolerancePct)

        val event = when {
            wpm < lower -> FeedbackEvent.SpeedUp
            wpm > upper -> FeedbackEvent.SlowDown
            else -> FeedbackEvent.OnTarget
        }

        lastFeedbackMs = nowMs
        return event
    }

    companion object {
        const val TARGET_WPM = 130.0
        const val TOLERANCE_PCT = 0.15
        const val COOLDOWN_MS = 5_000L
    }
}
