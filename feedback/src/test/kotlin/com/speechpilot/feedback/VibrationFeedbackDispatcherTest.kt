package com.speechpilot.feedback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VibrationFeedbackDispatcherTest {

    @Test
    fun `slow down uses uneven waveform pattern`() {
        val pattern = VibrationFeedbackDispatcher.patternFor(FeedbackEvent.SlowDown)

        require(pattern is VibrationFeedbackDispatcher.HapticPattern.Waveform)
        assertArrayEquals(VibrationFeedbackDispatcher.SLOW_DOWN_TIMINGS_MS, pattern.timings)
        assertArrayEquals(VibrationFeedbackDispatcher.SLOW_DOWN_AMPLITUDES, pattern.amplitudes)
        assertEquals(-1, pattern.repeat)
    }

    @Test
    fun `speed up uses single pulse`() {
        val pattern = VibrationFeedbackDispatcher.patternFor(FeedbackEvent.SpeedUp)

        require(pattern is VibrationFeedbackDispatcher.HapticPattern.OneShot)
        assertEquals(VibrationFeedbackDispatcher.ALERT_DURATION_MS, pattern.durationMs)
        assertEquals(VibrationFeedbackDispatcher.ALERT_AMPLITUDE, pattern.amplitude)
    }

    @Test
    fun `on target uses no vibration`() {
        assertNull(VibrationFeedbackDispatcher.patternFor(FeedbackEvent.OnTarget))
    }
}