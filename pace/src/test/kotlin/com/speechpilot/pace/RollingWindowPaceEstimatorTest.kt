package com.speechpilot.pace

import com.speechpilot.audio.AudioFrame
import com.speechpilot.segmentation.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RollingWindowPaceEstimatorTest {

    private lateinit var estimator: RollingWindowPaceEstimator

    @Before
    fun setUp() {
        estimator = RollingWindowPaceEstimator(windowMs = 60_000L)
    }

    @Test
    fun `empty segment returns zero wpm`() {
        val segment = SpeechSegment(
            frames = listOf(AudioFrame(ShortArray(0), 16_000, 0L)),
            startMs = 0L,
            endMs = 0L
        )
        val metrics = estimator.estimate(segment)
        assertEquals(0.0, metrics.wordsPerMinute, 0.001)
    }

    @Test
    fun `reset clears internal state`() {
        val segment = SpeechSegment(
            frames = listOf(AudioFrame(ShortArray(512), 16_000, 100L)),
            startMs = 0L,
            endMs = 1000L
        )
        estimator.estimate(segment)
        estimator.reset()
        val afterReset = estimator.estimate(segment)
        // After reset, window contains only this segment
        assert(afterReset.windowDurationMs <= 1000L)
    }
}
