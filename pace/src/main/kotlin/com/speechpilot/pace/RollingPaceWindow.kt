package com.speechpilot.pace

/**
 * Maintains a smoothed rolling pace signal from sequential [PaceMetrics] observations.
 *
 * Applies an exponential moving average (EMA) to reduce noise from individual segment-level
 * estimates produced by [PaceEstimator]. Also tracks peak and cumulative average for
 * session-level summary stats.
 *
 * This is intentionally a lightweight, in-memory structure. It is not thread-safe on its own
 * and should be accessed from a single coroutine context (e.g. the session pipeline coroutine).
 *
 * @param alpha Smoothing factor in range (0.0, 1.0]. Higher values weight recent observations
 *              more heavily. Default [DEFAULT_ALPHA] (0.3) provides moderate smoothing.
 */
class RollingPaceWindow(
    private val alpha: Double = DEFAULT_ALPHA
) {

    private var smoothedWpm: Double = 0.0
    private var peakWpm: Double = 0.0
    private var cumulativeWpm: Double = 0.0
    private var observationCount: Int = 0

    /**
     * Incorporate a new [PaceMetrics] observation into the rolling window.
     *
     * On the first observation the smoothed value is seeded directly from the raw estimate.
     * Subsequent observations are blended using EMA: `smoothed = alpha * new + (1 - alpha) * prev`.
     */
    fun update(metrics: PaceMetrics) {
        val wpm = metrics.estimatedWpm
        smoothedWpm = if (observationCount == 0) wpm else alpha * wpm + (1.0 - alpha) * smoothedWpm
        if (wpm > peakWpm) peakWpm = wpm
        cumulativeWpm += wpm
        observationCount++
    }

    /** Current EMA-smoothed pace estimate. Returns 0.0 before any observations. */
    fun smoothedEstimatedWpm(): Double = smoothedWpm

    /** Highest raw [PaceMetrics.estimatedWpm] observed since last [reset]. */
    fun peakEstimatedWpm(): Double = peakWpm

    /**
     * Arithmetic mean of all [PaceMetrics.estimatedWpm] values observed since last [reset].
     * Returns 0.0 if no observations have been made.
     */
    fun averageEstimatedWpm(): Double =
        if (observationCount == 0) 0.0 else cumulativeWpm / observationCount

    /** Number of observations incorporated since last [reset]. */
    fun observationCount(): Int = observationCount

    /** Reset all rolling state. Call when starting a new session. */
    fun reset() {
        smoothedWpm = 0.0
        peakWpm = 0.0
        cumulativeWpm = 0.0
        observationCount = 0
    }

    companion object {
        /** Default EMA smoothing factor. */
        const val DEFAULT_ALPHA = 0.3
    }
}
