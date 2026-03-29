package com.speechpilot.data

/**
 * Persisted record of a completed coaching session.
 *
 * [averageEstimatedWpm] and [peakEstimatedWpm] are proxy-based estimates — not true WPM values.
 * See [com.speechpilot.pace.PaceMetrics] for details on the estimation methodology.
 */
data class SessionRecord(
    val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long = endedAtMs - startedAtMs,
    /** Cumulative duration (ms) of speech-active segments in this session. */
    val totalSpeechActiveDurationMs: Long = 0L,
    /** Number of speech segments detected during the session. */
    val segmentCount: Int = 0,
    /** Arithmetic mean of per-segment estimated WPM. Approximate proxy only. */
    val averageEstimatedWpm: Double = 0.0,
    /** Peak per-segment estimated WPM observed. Approximate proxy only. */
    val peakEstimatedWpm: Double = 0.0
)
