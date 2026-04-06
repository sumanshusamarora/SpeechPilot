package com.speechpilot.feedback

import com.speechpilot.pace.PaceMetrics

/**
 * Threshold-based feedback decision engine with cooldown and sustain debounce.
 *
 * Responsibilities:
 * - Guard against invalid/missing pace signals (≤ 0 WPM).
 * - Compare the current pace estimate against a configurable target + tolerance band.
 * - Suppress repeat alerts during the cooldown window to avoid notification spam.
 * - Require [sustainCount] consecutive over-threshold observations before firing a
 *   [FeedbackEvent.SlowDown] alert. This avoids reacting to single noisy spikes.
 *   [FeedbackEvent.SpeedUp] and [FeedbackEvent.OnTarget] are not debounced because
 *   under-pacing and target are less susceptible to momentary noise.
 *
 * Decision logic is deterministic and has no side effects beyond internal timing state,
 * making it straightforward to unit-test with a fixed clock.
 *
 * @param targetWpm       Target speaking pace in estimated WPM. Default [TARGET_WPM].
 * @param tolerancePct    Tolerance band around target as a fraction (e.g. 0.15 = ±15%).
 *                        Default [TOLERANCE_PCT].
 * @param cooldownMs      Minimum milliseconds between two consecutive feedback events.
 *                        Default [COOLDOWN_MS]. Use 0 in tests for immediate evaluation.
 * @param sustainCount    Number of consecutive over-threshold observations required before
 *                        a [FeedbackEvent.SlowDown] event fires. Default [SUSTAIN_COUNT] (2).
 *                        Set to 1 to trigger on the first detection (no sustain debounce).
 * @param clock           Time source in milliseconds. Defaults to [System.currentTimeMillis].
 *                        Override in tests to control time deterministically.
 */
class ThresholdFeedbackDecision(
    private val targetWpm: Double = TARGET_WPM,
    private val tolerancePct: Double = TOLERANCE_PCT,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val sustainCount: Int = SUSTAIN_COUNT,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : FeedbackDecision {

    private var lastFeedbackMs: Long? = null

    /**
     * Tracks consecutive over-threshold (too fast) observations for sustain debounce.
     * Resets when pace falls within or below target, or when a SlowDown event fires.
     */
    private var consecutiveFastCount: Int = 0

    /**
     * Human-readable description of the most recent decision outcome.
     * Updated on every call to [evaluate]. Useful for debug surfaces and test assertions.
     */
    var lastDecisionReason: String = "none"
        private set

    /** Returns the configured target pace used for threshold comparison. */
    fun currentTargetWpm(): Double = targetWpm

    /**
     * Returns true if the cooldown window is currently active (i.e. the minimum
     * inter-alert interval has not yet elapsed since the last feedback event).
     */
    fun isCooldownActive(): Boolean = lastFeedbackMs?.let { last -> (clock() - last) < cooldownMs } ?: false

    override fun evaluate(metrics: PaceMetrics): FeedbackEvent? {
        // Guard: do not produce feedback for an invalid or missing pace signal.
        if (metrics.estimatedWpm <= 0.0) {
            lastDecisionReason = "invalid-signal"
            return null
        }

        val wpm = metrics.estimatedWpm
        val lower = targetWpm * (1 - tolerancePct)
        val upper = targetWpm * (1 + tolerancePct)
        val nowMs = clock()
        val cooldownActive = lastFeedbackMs?.let { last -> (nowMs - last) < cooldownMs } == true

        return when {
            wpm > upper -> {
                if (cooldownActive) {
                    lastDecisionReason = "cooldown-suppressed"
                    return null
                }
                consecutiveFastCount++
                if (consecutiveFastCount >= sustainCount) {
                    // Sustained over-threshold pace — fire the alert.
                    consecutiveFastCount = 0
                    lastFeedbackMs = nowMs
                    lastDecisionReason = "slow-down"
                    FeedbackEvent.SlowDown
                } else {
                    // Not yet sustained enough — wait for more evidence.
                    lastDecisionReason = "debouncing($consecutiveFastCount/$sustainCount)"
                    null
                }
            }
            wpm < lower -> {
                if (cooldownActive) {
                    lastDecisionReason = "cooldown-suppressed"
                    return null
                }
                consecutiveFastCount = 0
                lastFeedbackMs = nowMs
                lastDecisionReason = "speed-up"
                FeedbackEvent.SpeedUp
            }
            else -> {
                // On-target: reset sustain counter but do NOT update lastFeedbackMs.
                // Cooldown guards against corrective-alert spam; neutral state must not
                // reset the timer and inadvertently suppress a subsequent real alert.
                consecutiveFastCount = 0
                lastDecisionReason = "on-target"
                FeedbackEvent.OnTarget
            }
        }
    }

    companion object {
        const val TARGET_WPM = 130.0
        const val TOLERANCE_PCT = 0.15
        const val COOLDOWN_MS = 5_000L

        /**
         * Default number of consecutive over-threshold observations before a SlowDown fires.
         * Two observations strikes a balance between responsiveness and noise rejection.
         */
        const val SUSTAIN_COUNT = 2
    }
}
