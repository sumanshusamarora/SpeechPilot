package com.speechpilot.feedback

import com.speechpilot.pace.PaceMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for threshold, cooldown, sustain/debounce, and invalid-pace behaviour added in Issue 5.
 *
 * All tests use a controllable [clock] lambda so time-sensitive assertions are deterministic.
 */
class ThresholdFeedbackDecisionEngineTest {

    // ── invalid / missing pace guard ──────────────────────────────────────────

    @Test
    fun `zero wpm returns null`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 1)
        assertNull(decision.evaluate(PaceMetrics(estimatedWpm = 0.0, windowDurationMs = 1000L)))
    }

    @Test
    fun `negative wpm returns null`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 1)
        assertNull(decision.evaluate(PaceMetrics(estimatedWpm = -10.0, windowDurationMs = 1000L)))
    }

    // ── cooldown suppression ──────────────────────────────────────────────────

    @Test
    fun `second alert within cooldown is suppressed`() {
        var fakeTime = 0L
        val decision = buildDecision(cooldownMs = 5_000L, sustainCount = 1, clock = { fakeTime })

        // First evaluation triggers SpeedUp
        val first = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SpeedUp, first)

        // Advance clock inside the cooldown window
        fakeTime = 3_000L
        val second = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertNull("Expected null during cooldown", second)
    }

    @Test
    fun `alert fires again after cooldown expires`() {
        var fakeTime = 0L
        val decision = buildDecision(cooldownMs = 5_000L, sustainCount = 1, clock = { fakeTime })

        decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L)) // fires

        // Advance past the cooldown window
        fakeTime = 6_000L
        val afterCooldown = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SpeedUp, afterCooldown)
    }

    @Test
    fun `OnTarget during cooldown does not reset the cooldown timer`() {
        var fakeTime = 0L
        val decision = buildDecision(cooldownMs = 5_000L, sustainCount = 1, clock = { fakeTime })

        // First corrective alert — starts cooldown at t=0
        val first = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SpeedUp, first)

        // On-target observation within the cooldown window — must NOT reset lastFeedbackMs
        fakeTime = 2_000L
        val onTarget = decision.evaluate(PaceMetrics(estimatedWpm = 130.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.OnTarget, onTarget)

        // Another corrective event at t=3000 — still within the original 5s cooldown from t=0,
        // so the alert must still be suppressed (OnTarget at t=2000 didn't reset the timer).
        fakeTime = 3_000L
        val suppressed = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertNull("Expected null — cooldown should not have been reset by OnTarget", suppressed)

        // After the original 5s cooldown expires from t=0, the alert should fire again
        fakeTime = 6_000L
        val afterCooldown = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SpeedUp, afterCooldown)
    }

    // ── sustain / debounce for SlowDown ───────────────────────────────────────

    @Test
    fun `single fast observation does not fire SlowDown when sustainCount is 2`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 2)
        val result = decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))
        assertNull("Expected null — sustain not yet reached", result)
    }

    @Test
    fun `two consecutive fast observations fires SlowDown when sustainCount is 2`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 2)
        decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L)) // first
        val result = decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SlowDown, result)
    }

    @Test
    fun `sustain counter resets when pace falls within target`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 2)

        // One fast observation
        decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))

        // On-target observation resets the sustain counter and produces OnTarget
        val onTarget = decision.evaluate(PaceMetrics(estimatedWpm = 130.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.OnTarget, onTarget)

        // Next fast observation should NOT fire SlowDown (counter was reset)
        val afterReset = decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))
        assertNull("Expected null — sustain counter was reset", afterReset)
    }

    @Test
    fun `sustain counter resets when pace drops below target`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 2)

        // One fast observation
        decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))

        // Slow observation resets the sustain counter
        decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L)) // SpeedUp

        // Next fast observation — counter was reset, so still needs sustainCount to fire
        val afterReset = decision.evaluate(PaceMetrics(estimatedWpm = 160.0, windowDurationMs = 1000L))
        assertNull("Expected null — sustain counter was reset by a slow segment", afterReset)
    }

    @Test
    fun `SpeedUp fires on first under-threshold observation regardless of sustainCount`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 3)
        val result = decision.evaluate(PaceMetrics(estimatedWpm = 80.0, windowDurationMs = 1000L))
        assertEquals(FeedbackEvent.SpeedUp, result)
    }

    // ── deterministic sequence ────────────────────────────────────────────────

    @Test
    fun `sequence of fast slow on-target produces expected events`() {
        val decision = buildDecision(cooldownMs = 0L, sustainCount = 1)

        assertEquals(FeedbackEvent.SlowDown, decision.evaluate(metrics(160.0)))
        assertEquals(FeedbackEvent.SpeedUp, decision.evaluate(metrics(80.0)))
        assertEquals(FeedbackEvent.OnTarget, decision.evaluate(metrics(130.0)))
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildDecision(
        cooldownMs: Long,
        sustainCount: Int,
        clock: () -> Long = { 0L }
    ) = ThresholdFeedbackDecision(
        targetWpm = 130.0,
        tolerancePct = 0.15,
        cooldownMs = cooldownMs,
        sustainCount = sustainCount,
        clock = clock
    )

    private fun metrics(wpm: Double) = PaceMetrics(estimatedWpm = wpm, windowDurationMs = 1000L)
}
