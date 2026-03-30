package com.speechpilot.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingTranscriptWpmCalculatorTest {

    @Test
    fun `partial updates do not change wpm`() {
        val calculator = RollingTranscriptWpmCalculator(windowMs = 10_000)

        val snapshot = calculator.onUpdate(
            TranscriptUpdate(
                text = "hello there",
                stability = TranscriptStability.Partial,
                receivedAtMs = 1_000
            )
        )

        assertEquals(0.0, snapshot.rollingWpm, 0.001)
        assertTrue(snapshot.transcriptPreview.contains("[hello there]"))
    }

    @Test
    fun `final updates contribute to rolling wpm`() {
        val calculator = RollingTranscriptWpmCalculator(windowMs = 10_000)

        val snapshot = calculator.onUpdate(
            TranscriptUpdate(
                text = "one two three four",
                stability = TranscriptStability.Final,
                receivedAtMs = 5_000
            )
        )

        assertEquals(24.0, snapshot.rollingWpm, 0.001)
        assertEquals(4, snapshot.rollingWordCount)
    }

    @Test
    fun `words outside window are evicted`() {
        val calculator = RollingTranscriptWpmCalculator(windowMs = 5_000)

        calculator.onUpdate(TranscriptUpdate("one two", TranscriptStability.Final, 1_000))
        val snapshot = calculator.onUpdate(
            TranscriptUpdate("three four", TranscriptStability.Final, 7_000)
        )

        assertEquals(2, snapshot.rollingWordCount)
        assertEquals(24.0, snapshot.rollingWpm, 0.001)
    }
}
