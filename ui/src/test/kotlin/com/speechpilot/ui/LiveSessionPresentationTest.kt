package com.speechpilot.ui

import com.speechpilot.session.TranscriptDebugState
import com.speechpilot.session.TranscriptDebugStatus
import com.speechpilot.session.DebugPipelineInfo
import com.speechpilot.session.PaceSignalSource
import com.speechpilot.transcription.TranscriptionEngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionPresentationTest {

    @Test
    fun `shows listening state when transcript enabled but no words yet`() {
        val state = MainUiState(
            transcriptDebugEnabled = true,
            transcriptDebug = TranscriptDebugState(
                status = TranscriptDebugStatus.WaitingForSpeech,
                engineStatus = TranscriptionEngineStatus.Listening
            )
        )

        val transcript = resolveTranscriptSurfacePresentation(state)

        assertEquals("Listening for speech", transcript.helperText)
        assertEquals("No final words yet.", transcript.bodyText)
    }

    @Test
    fun `shows partial transcript when only partial text exists`() {
        val state = MainUiState(
            transcriptDebugEnabled = true,
            transcriptDebug = TranscriptDebugState(
                status = TranscriptDebugStatus.PartialAvailable,
                partialTranscriptPresent = true,
                transcriptText = "this is partial"
            )
        )

        val transcript = resolveTranscriptSurfacePresentation(state)

        assertEquals("Partial transcript available", transcript.helperText)
        assertEquals("this is partial", transcript.bodyText)
        assertTrue(transcript.showAsPartial)
    }

    @Test
    fun `prefers transcript wpm as primary pace when finalized words exist`() {
        val state = MainUiState(
            transcriptDebugEnabled = true,
            debugInfo = DebugPipelineInfo(
                activePaceSource = PaceSignalSource.Transcript,
                decisionWpm = 120.0,
                heuristicWpm = 160.0
            )
        )

        val pace = resolvePaceMetricPresentation(state)

        assertTrue(pace.usesTranscriptAsPrimary)
        assertEquals("120 WPM", pace.primaryValue)
        assertEquals("Text pace (transcript-derived)", pace.primaryLabel)
    }

    @Test
    fun `shows heuristic pace with transcript pending message when no final words yet`() {
        val state = MainUiState(
            transcriptDebugEnabled = true,
            debugInfo = DebugPipelineInfo(
                activePaceSource = PaceSignalSource.Heuristic,
                decisionWpm = 150.0,
                paceSourceReason = "transcript-pending-fallback"
            )
        )

        val pace = resolvePaceMetricPresentation(state)

        assertFalse(pace.usesTranscriptAsPrimary)
        assertEquals("150 est-WPM", pace.primaryValue)
        assertTrue(pace.detail.contains("fallback"))
    }

    @Test
    fun `shows transcript unavailable when recognizer unavailable`() {
        val state = MainUiState(
            transcriptDebugEnabled = true,
            debugInfo = DebugPipelineInfo(
                activePaceSource = PaceSignalSource.Heuristic,
                decisionWpm = 145.0,
                paceSourceReason = "transcript-unavailable-fallback"
            )
        )

        val pace = resolvePaceMetricPresentation(state)

        assertFalse(pace.usesTranscriptAsPrimary)
        assertTrue(pace.detail.contains("unavailable"))
    }
}
