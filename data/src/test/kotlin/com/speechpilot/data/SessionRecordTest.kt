package com.speechpilot.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRecordTest {

    @Test
    fun `durationMs defaults to endedAtMs minus startedAtMs`() {
        val record = SessionRecord(startedAtMs = 1_000L, endedAtMs = 6_000L)
        assertEquals(5_000L, record.durationMs)
    }

    @Test
    fun `explicit durationMs overrides computed default`() {
        val record = SessionRecord(startedAtMs = 1_000L, endedAtMs = 6_000L, durationMs = 9_999L)
        assertEquals(9_999L, record.durationMs)
    }

    @Test
    fun `default wpm fields are zero`() {
        val record = SessionRecord(startedAtMs = 0L, endedAtMs = 0L)
        assertEquals(0.0, record.averageEstimatedWpm, 0.001)
        assertEquals(0.0, record.peakEstimatedWpm, 0.001)
    }

    @Test
    fun `default segmentCount is zero`() {
        val record = SessionRecord(startedAtMs = 0L, endedAtMs = 0L)
        assertEquals(0, record.segmentCount)
    }

    @Test
    fun `default totalSpeechActiveDurationMs is zero`() {
        val record = SessionRecord(startedAtMs = 0L, endedAtMs = 0L)
        assertEquals(0L, record.totalSpeechActiveDurationMs)
    }

    @Test
    fun `id defaults to zero for auto-generated primary key`() {
        val record = SessionRecord(startedAtMs = 0L, endedAtMs = 0L)
        assertEquals(0L, record.id)
    }

    @Test
    fun `copy preserves all fields`() {
        val original = SessionRecord(
            id = 1L,
            startedAtMs = 1_000L,
            endedAtMs = 6_000L,
            totalSpeechActiveDurationMs = 3_000L,
            segmentCount = 5,
            averageEstimatedWpm = 120.0,
            peakEstimatedWpm = 145.0
        )
        val copy = original.copy(segmentCount = 7)
        assertEquals(1L, copy.id)
        assertEquals(1_000L, copy.startedAtMs)
        assertEquals(6_000L, copy.endedAtMs)
        assertEquals(5_000L, copy.durationMs)
        assertEquals(3_000L, copy.totalSpeechActiveDurationMs)
        assertEquals(7, copy.segmentCount)
        assertEquals(120.0, copy.averageEstimatedWpm, 0.001)
        assertEquals(145.0, copy.peakEstimatedWpm, 0.001)
    }
}
