package com.speechpilot.pace

import com.speechpilot.segmentation.SpeechSegment
import java.util.ArrayDeque

/**
 * Estimates speaking pace over a rolling time window of [SpeechSegment]s.
 *
 * **Proxy methodology (Phase 1):** True word-per-minute estimation requires word-boundary
 * detection or STT, neither of which is available in Phase 1. Instead, the estimator
 * detects syllable-like energy peaks in the amplitude envelope of each speech segment's
 * audio frames. The count of these peaks per unit of speech time forms a syllable-rate
 * proxy; dividing by [AVG_SYLLABLES_PER_WORD] converts it to an approximate WPM signal.
 *
 * **Signal direction:** Higher speaking pace → more syllable peaks per second → higher
 * [PaceMetrics.estimatedWpm]. This is the correct, non-inverted direction.
 *
 * **Limitation:** The value is not a calibrated WPM reading. It is a relative pace signal
 * that increases with speech speed and decreases with slower speech. Absolute values
 * depend on audio quality, speaker characteristics, and frame size.
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

        // Count syllable-proxy peaks across all segments in the window.
        // Fast speech → more peaks per second → higher syllable rate → higher estimated WPM.
        // Slow speech → fewer peaks per second → lower syllable rate → lower estimated WPM.
        val totalSyllables = segmentQueue.sumOf { countSyllableProxies(it) }.toDouble()
        val syllablesPerMinute = totalSyllables / (totalDurationMs / 60_000.0)
        val estimatedWpm = syllablesPerMinute / AVG_SYLLABLES_PER_WORD

        return PaceMetrics(
            estimatedWpm = estimatedWpm,
            windowDurationMs = totalDurationMs
        )
    }

    override fun reset() {
        segmentQueue.clear()
    }

    /**
     * Counts the number of energy-envelope peaks in [segment]'s frames as a syllable proxy.
     *
     * Each local maximum in the per-frame mean-square energy (above a relative threshold)
     * corresponds to a voiced energy burst, which approximates one syllable nucleus.
     *
     * Returns at least 1 for any non-empty segment to ensure a minimum positive contribution.
     * Returns 0 only for truly empty/zero-duration segments.
     */
    internal fun countSyllableProxies(segment: SpeechSegment): Int {
        if (segment.frames.isEmpty()) return 0

        // Compute mean-square energy per frame (proportional to squared RMS),
        // tracking the maximum in the same pass to avoid a second iteration.
        var maxEnergy = 0.0
        val frameEnergies = segment.frames.map { frame ->
            val energy = if (frame.samples.isEmpty()) 0.0
            else frame.samples.fold(0.0) { acc, s -> acc + s.toLong() * s } / frame.samples.size
            if (energy > maxEnergy) maxEnergy = energy
            energy
        }

        if (frameEnergies.size < 3) {
            // Too few frames to find peaks — treat the whole segment as one syllable burst.
            return if (maxEnergy > 0.0) 1 else 0
        }

        if (maxEnergy == 0.0) return 0

        // Relative threshold: only count peaks that are meaningfully above silence.
        val threshold = maxEnergy * SYLLABLE_PEAK_THRESHOLD

        var count = 0
        for (i in 1 until frameEnergies.size - 1) {
            val prev = frameEnergies[i - 1]
            val curr = frameEnergies[i]
            val next = frameEnergies[i + 1]
            // Local maximum that clears the relative threshold.
            if (curr >= prev && curr > next && curr > threshold) {
                count++
            }
        }

        // Guarantee at least 1 per non-silent segment.
        return if (count > 0) count else 1
    }

    private fun evictOldSegments(nowMs: Long) {
        while (segmentQueue.isNotEmpty()) {
            val oldest = segmentQueue.peekFirst() ?: break
            if (nowMs - oldest.startMs <= windowMs) break
            segmentQueue.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 30_000L

        /**
         * Fraction of the peak frame energy used as a minimum threshold for syllable peaks.
         * Peaks below this fraction of the maximum are treated as background noise.
         */
        const val SYLLABLE_PEAK_THRESHOLD = 0.30

        /**
         * Average syllables per word for English conversational speech.
         * Used to convert syllable rate to approximate word rate.
         * Typical range: 1.3–1.8; 1.5 is a commonly cited midpoint.
         */
        const val AVG_SYLLABLES_PER_WORD = 1.5
    }
}
