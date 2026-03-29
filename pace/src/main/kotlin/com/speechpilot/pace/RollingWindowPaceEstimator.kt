package com.speechpilot.pace

import com.speechpilot.segmentation.SpeechSegment
import java.util.ArrayDeque

/**
 * Estimates speaking pace over a rolling time window of [SpeechSegment]s.
 *
 * **Proxy methodology (Phase 1):** True word-per-minute estimation requires word-boundary
 * detection or STT, neither of which is available in Phase 1. Instead, the estimator
 * uses a heuristic of [ESTIMATED_WORDS_PER_SEGMENT] words per detected speech segment as
 * a proxy. This produces a relative pace signal — segments arriving more frequently or
 * in longer bursts produce higher estimates. The value is not a calibrated WPM reading.
 *
 * Segments older than [windowMs] are evicted from the window on each call to [estimate].
 */
class RollingWindowPaceEstimator(
    private val windowMs: Long = WINDOW_MS
) : PaceEstimator {

    private val segmentQueue: ArrayDeque<SpeechSegment> = ArrayDeque()

    override fun estimate(segment: SpeechSegment): PaceMetrics {
        val now = segment.endMs
        segmentQueue.addLast(segment)
        evictOldSegments(now)

        val totalDurationMs = segmentQueue.sumOf { it.durationMs }
        if (totalDurationMs == 0L) {
            return PaceMetrics(estimatedWpm = 0.0, windowDurationMs = 0L)
        }

        // Proxy: each speech segment is assumed to contain approximately ESTIMATED_WORDS_PER_SEGMENT
        // words. This heuristic is a Phase 1 approximation; it does not measure real word boundaries.
        val estimatedWords = segmentQueue.size.toDouble() * ESTIMATED_WORDS_PER_SEGMENT
        val wpm = estimatedWords / (totalDurationMs / 60_000.0)

        return PaceMetrics(
            estimatedWpm = wpm,
            windowDurationMs = totalDurationMs
        )
    }

    override fun reset() {
        segmentQueue.clear()
    }

    private fun evictOldSegments(nowMs: Long) {
        while (segmentQueue.isNotEmpty() && nowMs - segmentQueue.peekFirst().startMs > windowMs) {
            segmentQueue.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 30_000L

        /**
         * Estimated words per speech segment used as a Phase 1 proxy.
         * Conversational speech typically produces 1–4 words per utterance burst;
         * 2.0 is used as a conservative midpoint.
         */
        const val ESTIMATED_WORDS_PER_SEGMENT = 2.0
    }
}
