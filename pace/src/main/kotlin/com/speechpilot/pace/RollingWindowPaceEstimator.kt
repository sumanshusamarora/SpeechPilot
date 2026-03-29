package com.speechpilot.pace

import com.speechpilot.segmentation.SpeechSegment
import java.util.ArrayDeque

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
            return PaceMetrics(wordsPerMinute = 0.0, syllablesPerSecond = 0.0, windowDurationMs = 0L)
        }

        // Placeholder: real estimation requires word-boundary or syllable detection
        val estimatedWords = segmentQueue.size.toDouble() * 2
        val wpm = estimatedWords / (totalDurationMs / 60_000.0)

        return PaceMetrics(
            wordsPerMinute = wpm,
            syllablesPerSecond = 0.0,
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
    }
}
