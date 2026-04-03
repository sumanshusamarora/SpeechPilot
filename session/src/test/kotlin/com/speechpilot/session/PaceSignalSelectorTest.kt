package com.speechpilot.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceSignalSelectorTest {

    @Test
    fun `selects transcript when transcript signal is ready`() {
        val selection = selectPaceSignal(
            transcriptEnabled = true,
            transcriptStatus = TranscriptDebugStatus.FinalAvailable,
            transcriptWpm = 132.0,
            transcriptFinalizedWordCount = 5,
            transcriptRollingWordCount = 6,
            heuristicWpm = 150.0
        )

        assertEquals(PaceSignalSource.Transcript, selection.source)
        assertEquals(132.0, selection.selectedWpm, 0.001)
        assertTrue(selection.transcriptReady)
        assertFalse(selection.fallbackActive)
    }

    @Test
    fun `falls back to heuristic while transcript pending`() {
        val selection = selectPaceSignal(
            transcriptEnabled = true,
            transcriptStatus = TranscriptDebugStatus.PartialAvailable,
            transcriptWpm = 0.0,
            transcriptFinalizedWordCount = 0,
            transcriptRollingWordCount = 2,
            heuristicWpm = 155.0
        )

        assertEquals(PaceSignalSource.Heuristic, selection.source)
        assertEquals("transcript-pending-fallback", selection.reason)
        assertTrue(selection.fallbackActive)
        assertFalse(selection.transcriptReady)
    }

    @Test
    fun `falls back to heuristic when transcript unavailable`() {
        val selection = selectPaceSignal(
            transcriptEnabled = true,
            transcriptStatus = TranscriptDebugStatus.Unavailable,
            transcriptWpm = 0.0,
            transcriptFinalizedWordCount = 0,
            transcriptRollingWordCount = 0,
            heuristicWpm = 140.0
        )

        assertEquals(PaceSignalSource.Heuristic, selection.source)
        assertEquals("transcript-unavailable-fallback", selection.reason)
        assertTrue(selection.fallbackActive)
    }

    @Test
    fun `uses no signal when transcript pending and heuristic missing`() {
        val selection = selectPaceSignal(
            transcriptEnabled = true,
            transcriptStatus = TranscriptDebugStatus.WaitingForSpeech,
            transcriptWpm = 0.0,
            transcriptFinalizedWordCount = 0,
            transcriptRollingWordCount = 0,
            heuristicWpm = 0.0
        )

        assertEquals(PaceSignalSource.None, selection.source)
        assertEquals("transcript-pending-no-fallback", selection.reason)
    }
}
