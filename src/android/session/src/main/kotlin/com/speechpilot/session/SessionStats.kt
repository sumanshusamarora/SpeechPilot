package com.speechpilot.session

/**
 * Summary statistics accumulated over a single coaching session.
 *
 * All WPM-related fields use "estimated" in their names to reflect that the pace signal
 * is a proxy measure, not a true word-per-minute count (see [com.speechpilot.pace.PaceMetrics]).
 */
data class SessionStats(
    /** Epoch ms at which the session was started. 0 when no session has started. */
    val startedAtMs: Long = 0L,
    /** Total elapsed wall-clock duration of the session in milliseconds. */
    val durationMs: Long = 0L,
    /** Cumulative duration (ms) of speech-active segments within the session. */
    val totalSpeechActiveDurationMs: Long = 0L,
    /** Number of distinct speech segments detected. */
    val segmentCount: Int = 0,
    /** Arithmetic mean of all per-segment estimated WPM values. */
    val averageEstimatedWpm: Double = 0.0,
    /** Highest per-segment estimated WPM observed during the session. */
    val peakEstimatedWpm: Double = 0.0
)
