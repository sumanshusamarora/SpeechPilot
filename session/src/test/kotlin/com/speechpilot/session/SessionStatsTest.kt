package com.speechpilot.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatsTest {

    // ── default values ────────────────────────────────────────────────────────

    @Test
    fun `default startedAtMs is zero`() {
        assertEquals(0L, SessionStats().startedAtMs)
    }

    @Test
    fun `default durationMs is zero`() {
        assertEquals(0L, SessionStats().durationMs)
    }

    @Test
    fun `default totalSpeechActiveDurationMs is zero`() {
        assertEquals(0L, SessionStats().totalSpeechActiveDurationMs)
    }

    @Test
    fun `default segmentCount is zero`() {
        assertEquals(0, SessionStats().segmentCount)
    }

    @Test
    fun `default averageEstimatedWpm is zero`() {
        assertEquals(0.0, SessionStats().averageEstimatedWpm, 0.001)
    }

    @Test
    fun `default peakEstimatedWpm is zero`() {
        assertEquals(0.0, SessionStats().peakEstimatedWpm, 0.001)
    }

    // ── custom values ─────────────────────────────────────────────────────────

    @Test
    fun `custom values are stored correctly`() {
        val stats = SessionStats(
            startedAtMs = 1_000L,
            durationMs = 60_000L,
            totalSpeechActiveDurationMs = 45_000L,
            segmentCount = 20,
            averageEstimatedWpm = 125.0,
            peakEstimatedWpm = 155.0
        )
        assertEquals(1_000L, stats.startedAtMs)
        assertEquals(60_000L, stats.durationMs)
        assertEquals(45_000L, stats.totalSpeechActiveDurationMs)
        assertEquals(20, stats.segmentCount)
        assertEquals(125.0, stats.averageEstimatedWpm, 0.001)
        assertEquals(155.0, stats.peakEstimatedWpm, 0.001)
    }

    // ── copy semantics ────────────────────────────────────────────────────────

    @Test
    fun `copy updates only specified field`() {
        val base = SessionStats(
            startedAtMs = 1_000L,
            durationMs = 30_000L,
            totalSpeechActiveDurationMs = 20_000L,
            segmentCount = 10,
            averageEstimatedWpm = 120.0,
            peakEstimatedWpm = 140.0
        )
        val updated = base.copy(segmentCount = 15)

        assertEquals(1_000L, updated.startedAtMs)
        assertEquals(30_000L, updated.durationMs)
        assertEquals(20_000L, updated.totalSpeechActiveDurationMs)
        assertEquals(15, updated.segmentCount)
        assertEquals(120.0, updated.averageEstimatedWpm, 0.001)
        assertEquals(140.0, updated.peakEstimatedWpm, 0.001)
    }

    @Test
    fun `copy increments segmentCount and speech duration correctly`() {
        val base = SessionStats(
            startedAtMs = 0L,
            totalSpeechActiveDurationMs = 5_000L,
            segmentCount = 3
        )
        val updated = base.copy(
            totalSpeechActiveDurationMs = base.totalSpeechActiveDurationMs + 2_000L,
            segmentCount = base.segmentCount + 1
        )
        assertEquals(7_000L, updated.totalSpeechActiveDurationMs)
        assertEquals(4, updated.segmentCount)
    }

    // ── equality ──────────────────────────────────────────────────────────────

    @Test
    fun `two stats with same values are equal`() {
        val a = SessionStats(startedAtMs = 500L, segmentCount = 5)
        val b = SessionStats(startedAtMs = 500L, segmentCount = 5)
        assertEquals(a, b)
    }
}
