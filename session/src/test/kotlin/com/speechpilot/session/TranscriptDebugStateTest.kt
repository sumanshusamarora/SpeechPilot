package com.speechpilot.session

import com.speechpilot.transcription.TranscriptionEngineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptDebugStateTest {

    @Test
    fun `disabled status when debug mode off`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = false,
            engineStatus = TranscriptionEngineStatus.Listening,
            isSessionListening = true,
            partialTranscriptPresent = true,
            finalizedWordCount = 8
        )

        assertEquals(TranscriptDebugStatus.Disabled, status)
    }

    @Test
    fun `waiting for speech when recognizer listening without transcript`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = true,
            engineStatus = TranscriptionEngineStatus.Listening,
            isSessionListening = true,
            partialTranscriptPresent = false,
            finalizedWordCount = 0
        )

        assertEquals(TranscriptDebugStatus.WaitingForSpeech, status)
    }

    @Test
    fun `partial available when only partial transcript exists`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = true,
            engineStatus = TranscriptionEngineStatus.Listening,
            isSessionListening = true,
            partialTranscriptPresent = true,
            finalizedWordCount = 0
        )

        assertEquals(TranscriptDebugStatus.PartialAvailable, status)
    }

    @Test
    fun `final available when finalized words exist`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = true,
            engineStatus = TranscriptionEngineStatus.Listening,
            isSessionListening = true,
            partialTranscriptPresent = true,
            finalizedWordCount = 2
        )

        assertEquals(TranscriptDebugStatus.FinalAvailable, status)
    }

    @Test
    fun `unavailable status when recognizer unavailable`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = true,
            engineStatus = TranscriptionEngineStatus.Unavailable,
            isSessionListening = true,
            partialTranscriptPresent = false,
            finalizedWordCount = 0
        )

        assertEquals(TranscriptDebugStatus.Unavailable, status)
    }

    @Test
    fun `error status when recognizer reports error`() {
        val status = resolveTranscriptDebugStatus(
            debugEnabled = true,
            engineStatus = TranscriptionEngineStatus.Error,
            isSessionListening = true,
            partialTranscriptPresent = true,
            finalizedWordCount = 0
        )

        assertEquals(TranscriptDebugStatus.Error, status)
    }
}
