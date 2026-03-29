package com.speechpilot.feedback

import com.speechpilot.pace.PaceMetrics
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ThresholdFeedbackDecisionTest {

    private lateinit var decision: ThresholdFeedbackDecision

    @Before
    fun setUp() {
        // Use 0 cooldown and sustainCount=1 so every call can produce feedback immediately.
        decision = ThresholdFeedbackDecision(
            targetWpm = 130.0,
            tolerancePct = 0.15,
            cooldownMs = 0L,
            sustainCount = 1
        )
    }

    @Test
    fun `wpm above upper bound returns SlowDown`() {
        val metrics = PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.SlowDown, decision.evaluate(metrics))
    }

    @Test
    fun `wpm below lower bound returns SpeedUp`() {
        val metrics = PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.SpeedUp, decision.evaluate(metrics))
    }

    @Test
    fun `wpm within tolerance returns OnTarget`() {
        val metrics = PaceMetrics(estimatedWpm = 130.0, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.OnTarget, decision.evaluate(metrics))
    }

    @Test
    fun `wpm exactly at upper bound returns OnTarget`() {
        // upper = 130 * (1 + 0.15) = 149.5; pace at exactly that value is still on-target.
        val upper = 130.0 * (1.0 + 0.15)
        val metrics = PaceMetrics(estimatedWpm = upper, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.OnTarget, decision.evaluate(metrics))
    }

    @Test
    fun `wpm just above upper bound returns SlowDown`() {
        val justAbove = 130.0 * (1.0 + 0.15) + 0.01
        val metrics = PaceMetrics(estimatedWpm = justAbove, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.SlowDown, decision.evaluate(metrics))
    }

    @Test
    fun `wpm exactly at lower bound returns OnTarget`() {
        // lower = 130 * (1 - 0.15) = 110.5; pace at exactly that value is still on-target.
        val lower = 130.0 * (1.0 - 0.15)
        val metrics = PaceMetrics(estimatedWpm = lower, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.OnTarget, decision.evaluate(metrics))
    }

    @Test
    fun `wpm just below lower bound returns SpeedUp`() {
        val justBelow = 130.0 * (1.0 - 0.15) - 0.01
        val metrics = PaceMetrics(estimatedWpm = justBelow, windowDurationMs = 1000L)
        assertEquals(FeedbackEvent.SpeedUp, decision.evaluate(metrics))
    }
}
