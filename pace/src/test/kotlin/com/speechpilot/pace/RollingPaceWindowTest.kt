package com.speechpilot.pace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RollingPaceWindowTest {

    private lateinit var window: RollingPaceWindow

    @Before
    fun setUp() {
        window = RollingPaceWindow(alpha = 0.5)
    }

    // ── initial state ──────────────────────────────────────────────────────────

    @Test
    fun `initial smoothedEstimatedWpm is zero`() {
        assertEquals(0.0, window.smoothedEstimatedWpm(), 0.001)
    }

    @Test
    fun `initial peakEstimatedWpm is zero`() {
        assertEquals(0.0, window.peakEstimatedWpm(), 0.001)
    }

    @Test
    fun `initial averageEstimatedWpm is zero`() {
        assertEquals(0.0, window.averageEstimatedWpm(), 0.001)
    }

    @Test
    fun `initial observationCount is zero`() {
        assertEquals(0, window.observationCount())
    }

    // ── first observation seeds smoothed value ────────────────────────────────

    @Test
    fun `first update seeds smoothed value directly`() {
        window.update(metrics(100.0))
        assertEquals(100.0, window.smoothedEstimatedWpm(), 0.001)
    }

    @Test
    fun `first update sets peak`() {
        window.update(metrics(120.0))
        assertEquals(120.0, window.peakEstimatedWpm(), 0.001)
    }

    @Test
    fun `first update sets average`() {
        window.update(metrics(80.0))
        assertEquals(80.0, window.averageEstimatedWpm(), 0.001)
    }

    // ── EMA smoothing ──────────────────────────────────────────────────────────

    @Test
    fun `second update blends via EMA with alpha 0_5`() {
        // alpha=0.5: smoothed = 0.5*new + 0.5*prev
        window.update(metrics(100.0))  // smoothed = 100
        window.update(metrics(200.0))  // smoothed = 0.5*200 + 0.5*100 = 150
        assertEquals(150.0, window.smoothedEstimatedWpm(), 0.001)
    }

    @Test
    fun `smoothed value is more stable than raw spike`() {
        window.update(metrics(100.0))
        window.update(metrics(100.0))
        window.update(metrics(100.0))
        window.update(metrics(300.0)) // spike
        // smoothed should be well below 300
        assertTrue(window.smoothedEstimatedWpm() < 250.0)
    }

    // ── peak tracking ──────────────────────────────────────────────────────────

    @Test
    fun `peak is updated only when new observation exceeds previous peak`() {
        window.update(metrics(100.0))
        window.update(metrics(200.0))
        window.update(metrics(150.0)) // below peak
        assertEquals(200.0, window.peakEstimatedWpm(), 0.001)
    }

    @Test
    fun `peak is not affected by zero wpm observation`() {
        window.update(metrics(100.0))
        window.update(metrics(0.0))
        assertEquals(100.0, window.peakEstimatedWpm(), 0.001)
    }

    // ── average tracking ──────────────────────────────────────────────────────

    @Test
    fun `averageEstimatedWpm is arithmetic mean of all observations`() {
        window.update(metrics(60.0))
        window.update(metrics(120.0))
        window.update(metrics(180.0))
        assertEquals(120.0, window.averageEstimatedWpm(), 0.001)
    }

    @Test
    fun `observationCount increments with each update`() {
        window.update(metrics(10.0))
        window.update(metrics(20.0))
        window.update(metrics(30.0))
        assertEquals(3, window.observationCount())
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all state`() {
        window.update(metrics(100.0))
        window.update(metrics(200.0))
        window.reset()
        assertEquals(0.0, window.smoothedEstimatedWpm(), 0.001)
        assertEquals(0.0, window.peakEstimatedWpm(), 0.001)
        assertEquals(0.0, window.averageEstimatedWpm(), 0.001)
        assertEquals(0, window.observationCount())
    }

    @Test
    fun `after reset first update seeds smoothed from scratch`() {
        window.update(metrics(100.0))
        window.reset()
        window.update(metrics(50.0))
        assertEquals(50.0, window.smoothedEstimatedWpm(), 0.001)
    }

    // ── edge cases ─────────────────────────────────────────────────────────────

    @Test
    fun `zero wpm observation is included in average`() {
        window.update(metrics(0.0))
        window.update(metrics(100.0))
        assertEquals(50.0, window.averageEstimatedWpm(), 0.001)
    }

    @Test
    fun `high alpha weights recent observations heavily`() {
        val highAlphaWindow = RollingPaceWindow(alpha = 0.9)
        highAlphaWindow.update(metrics(10.0))  // smoothed = 10
        highAlphaWindow.update(metrics(100.0)) // smoothed = 0.9*100 + 0.1*10 = 91
        assertTrue(highAlphaWindow.smoothedEstimatedWpm() > 85.0)
    }

    @Test
    fun `low alpha weights recent observations lightly`() {
        val lowAlphaWindow = RollingPaceWindow(alpha = 0.1)
        lowAlphaWindow.update(metrics(10.0))    // smoothed = 10
        lowAlphaWindow.update(metrics(1000.0))  // smoothed = 0.1*1000 + 0.9*10 = 109
        assertTrue(lowAlphaWindow.smoothedEstimatedWpm() < 200.0)
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private fun metrics(wpm: Double) = PaceMetrics(estimatedWpm = wpm, windowDurationMs = 1_000L)
}
