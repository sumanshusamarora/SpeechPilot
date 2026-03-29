package com.speechpilot.pace

import com.speechpilot.audio.AudioFrame
import com.speechpilot.segmentation.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RollingWindowPaceEstimatorTest {

    private lateinit var estimator: RollingWindowPaceEstimator

    @Before
    fun setUp() {
        estimator = RollingWindowPaceEstimator(windowMs = 60_000L)
    }

    // ── basic output shape ─────────────────────────────────────────────────────

    @Test
    fun `zero-duration segment returns zero estimatedWpm`() {
        val segment = SpeechSegment(
            frames = listOf(AudioFrame(ShortArray(0), 16_000, 0L)),
            startMs = 0L,
            endMs = 0L
        )
        val metrics = estimator.estimate(segment)
        assertEquals(0.0, metrics.estimatedWpm, 0.001)
        assertEquals(0L, metrics.windowDurationMs)
    }

    @Test
    fun `non-zero duration segment returns positive estimatedWpm`() {
        val segment = segment(startMs = 0L, endMs = 2_000L)
        val metrics = estimator.estimate(segment)
        assertTrue(metrics.estimatedWpm > 0.0)
    }

    @Test
    fun `windowDurationMs equals segment duration for single segment`() {
        val segment = segment(startMs = 0L, endMs = 3_000L)
        val metrics = estimator.estimate(segment)
        assertEquals(3_000L, metrics.windowDurationMs)
    }

    // ── rolling window accumulation ────────────────────────────────────────────

    @Test
    fun `two segments within window accumulate windowDurationMs`() {
        estimator.estimate(segment(startMs = 0L, endMs = 1_000L))
        val metrics = estimator.estimate(segment(startMs = 1_500L, endMs = 2_500L))
        assertEquals(2_000L, metrics.windowDurationMs) // two 1000ms segments
    }

    @Test
    fun `segment outside window is evicted`() {
        // Add a segment that starts 65 s before the latest segment end – outside 60 s window
        estimator.estimate(segment(startMs = 0L, endMs = 1_000L))
        val metrics = estimator.estimate(segment(startMs = 65_000L, endMs = 66_000L))
        // Only the recent segment should remain in window
        assertEquals(1_000L, metrics.windowDurationMs)
    }

    // ── reset behaviour ────────────────────────────────────────────────────────

    @Test
    fun `reset clears internal state`() {
        estimator.estimate(segment(startMs = 0L, endMs = 1_000L))
        estimator.reset()
        val afterReset = estimator.estimate(segment(startMs = 0L, endMs = 1_000L))
        // After reset window contains only the single new segment
        assertEquals(1_000L, afterReset.windowDurationMs)
    }

    @Test
    fun `reset then zero-duration segment returns zero`() {
        estimator.estimate(segment(startMs = 0L, endMs = 5_000L))
        estimator.reset()
        val metrics = estimator.estimate(segment(startMs = 0L, endMs = 0L))
        assertEquals(0.0, metrics.estimatedWpm, 0.001)
    }

    // ── proxy estimation correctness ───────────────────────────────────────────

    @Test
    fun `estimated wpm increases with more segments in same window duration`() {
        // 1 segment over 60s
        val estimatorSlow = RollingWindowPaceEstimator(windowMs = 300_000L)
        val estimatorFast = RollingWindowPaceEstimator(windowMs = 300_000L)

        estimatorSlow.estimate(segment(startMs = 0L, endMs = 60_000L))
        estimatorFast.estimate(segment(startMs = 0L, endMs = 60_000L))
        estimatorFast.estimate(segment(startMs = 60_000L, endMs = 120_000L))

        val slow = estimatorSlow.estimate(segment(startMs = 60_000L, endMs = 61_000L))
        val fast = estimatorFast.estimate(segment(startMs = 120_000L, endMs = 121_000L))

        assertTrue("Fast speaker should have higher estimated WPM", fast.estimatedWpm > slow.estimatedWpm)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun segment(startMs: Long, endMs: Long) = SpeechSegment(
        frames = listOf(AudioFrame(ShortArray(512), 16_000, startMs)),
        startMs = startMs,
        endMs = endMs
    )
}
