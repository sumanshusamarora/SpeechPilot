package com.speechpilot.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertTrue(snapshot.partialTranscriptPresent)
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
        assertFalse(snapshot.partialTranscriptPresent)
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

    // ──────────────────────────────────────────────────────────────────────────
    // Chunk-based mode and WPM hold
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `chunkBased is false by default`() {
        val calculator = RollingTranscriptWpmCalculator()
        assertFalse(calculator.chunkBased)
    }

    @Test
    fun `snapshot isChunkBased reflects calculator mode`() {
        val calculator = RollingTranscriptWpmCalculator(
            windowMs = 30_000,
            chunkBased = true,
        )
        val snapshot = calculator.onUpdate(
            TranscriptUpdate("hello", TranscriptStability.Final, 1_000)
        )
        assertTrue(snapshot.isChunkBased)
    }

    @Test
    fun `lastChunkAtMs is null before first final update`() {
        val calculator = RollingTranscriptWpmCalculator(chunkBased = true)
        val snapshot = calculator.onUpdate(
            TranscriptUpdate("test", TranscriptStability.Partial, 1_000)
        )
        assertNull(snapshot.lastChunkAtMs)
    }

    @Test
    fun `lastChunkAtMs is set after first final update`() {
        val calculator = RollingTranscriptWpmCalculator(chunkBased = true)
        val snapshot = calculator.onUpdate(
            TranscriptUpdate("hello world", TranscriptStability.Final, 5_000)
        )
        assertEquals(5_000L, snapshot.lastChunkAtMs)
    }

    @Test
    fun `WPM hold preserves last known WPM when window drains between chunks`() {
        // Window: 30 s; hold: 10 s; chunk duration: 5 s.
        val calculator = RollingTranscriptWpmCalculator(
            windowMs = 30_000,
            chunkBased = true,
            chunkDurationMs = 5_000,
            wpmHoldDurationMs = 10_000,
        )

        // Chunk arrives at t=5000: 10 words → WPM = 10 * 60000 / 30000 = 20.
        val chunkSnapshot = calculator.onUpdate(
            TranscriptUpdate("a b c d e f g h i j", TranscriptStability.Final, 5_000)
        )
        val wpmAfterChunk = chunkSnapshot.rollingWpm

        // At t=12000 (7 seconds after chunk, within hold window), no new chunk yet.
        // The window still holds the 10 words, live WPM would still be 20. Since heldWpm == liveWpm,
        // we should get the same value (no hold needed in this window).
        val afterSnapshot = calculator.onUpdate(
            TranscriptUpdate("", TranscriptStability.Partial, 12_000)
        )
        // Live WPM is still positive — hold shouldn't matter yet.
        assertEquals(wpmAfterChunk, afterSnapshot.rollingWpm, 0.1)
    }

    @Test
    fun `WPM hold returns held value when live window drops to zero`() {
        // Small window so words evict quickly: 3 s window, 2 s hold.
        val calculator = RollingTranscriptWpmCalculator(
            windowMs = 3_000,
            chunkBased = true,
            chunkDurationMs = 5_000,
            wpmHoldDurationMs = 10_000,
        )

        // Chunk at t=1000: 6 words in 3 s window → WPM = 6 * 60000 / 3000 = 120.
        calculator.onUpdate(TranscriptUpdate("a b c d e f", TranscriptStability.Final, 1_000))

        // At t=6000 (5 seconds later): words have left the 3-second window → live WPM = 0.
        // Within hold window (10 s). heldWpm was 120, live is 0. Should return held = 120.
        val held = calculator.onUpdate(
            TranscriptUpdate("", TranscriptStability.Partial, 6_000)
        )
        assertEquals(120.0, held.rollingWpm, 0.1)
    }

    @Test
    fun `WPM hold expires after wpmHoldDurationMs`() {
        val calculator = RollingTranscriptWpmCalculator(
            windowMs = 3_000,
            chunkBased = true,
            chunkDurationMs = 5_000,
            wpmHoldDurationMs = 4_000,  // hold expires 4 s after last chunk
        )

        // Chunk at t=1000.
        calculator.onUpdate(TranscriptUpdate("a b c d e f", TranscriptStability.Final, 1_000))

        // At t=6001 (hold expired: 1000 + 4000 = 5000 expiry, now > 5000).
        val expired = calculator.onUpdate(
            TranscriptUpdate("", TranscriptStability.Partial, 6_001)
        )
        // Hold expired; live WPM is 0 (words evicted from window). Returned WPM should be 0.
        assertEquals(0.0, expired.rollingWpm, 0.01)
    }

    @Test
    fun `setChunkBased can toggle mode`() {
        val calculator = RollingTranscriptWpmCalculator()
        assertFalse(calculator.chunkBased)
        calculator.setChunkBased(true)
        assertTrue(calculator.chunkBased)
        calculator.setChunkBased(false)
        assertFalse(calculator.chunkBased)
    }

    @Test
    fun `reset clears held WPM and chunk timestamp`() {
        val calculator = RollingTranscriptWpmCalculator(
            windowMs = 30_000,
            chunkBased = true,
        )
        calculator.onUpdate(TranscriptUpdate("hello world", TranscriptStability.Final, 5_000))
        calculator.reset()

        // After reset a partial update should return empty state.
        val snapshot = calculator.onUpdate(
            TranscriptUpdate("", TranscriptStability.Partial, 6_000)
        )
        assertEquals(0.0, snapshot.rollingWpm, 0.001)
        assertNull(snapshot.lastChunkAtMs)
        assertEquals(0, snapshot.finalizedWordCount)
    }
}
