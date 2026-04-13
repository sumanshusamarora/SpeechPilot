package com.speechpilot.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {

    @Test
    fun `parse transcript final event`() {
        val message = """
            {
              "version": "1.0",
              "timestamp": "2025-01-01T00:00:00Z",
              "type": "transcript.final",
              "payload": {
                "sessionId": "session-123",
                "segment": {
                  "id": "seg-1",
                  "text": "hello world",
                  "startTimeMs": 0,
                  "endTimeMs": 1200,
                  "wordCount": 2
                }
              }
            }
        """.trimIndent()

        val event = RealtimeProtocol.parseServerEvent(message)

        assertTrue(event is RealtimeServerEvent.TranscriptFinal)
        val finalEvent = event as RealtimeServerEvent.TranscriptFinal
        assertEquals("session-123", finalEvent.sessionId)
        assertEquals("hello world", finalEvent.segment.text)
        assertEquals(2, finalEvent.segment.wordCount)
    }

    @Test
    fun `parse debug state event`() {
        val message = """
            {
              "version": "1.0",
              "timestamp": "2025-01-01T00:00:00Z",
              "type": "debug.state",
              "payload": {
                "lifecycle": "processing",
                "activeProvider": "faster-whisper",
                "chunksReceived": 4,
                "partialUpdates": 2,
                "finalSegments": 1,
                "totalWords": 11,
                "wordsPerMinute": 142.3,
                "paceBand": "good",
                "feedbackCount": 1,
                "detail": "Streaming audio"
              }
            }
        """.trimIndent()

        val event = RealtimeProtocol.parseServerEvent(message)

        assertTrue(event is RealtimeServerEvent.DebugState)
        val debugEvent = event as RealtimeServerEvent.DebugState
        assertEquals("processing", debugEvent.payload.lifecycle)
        assertEquals("faster-whisper", debugEvent.payload.activeProvider)
        assertEquals(4, debugEvent.payload.chunksReceived)
        assertEquals(142.3, debugEvent.payload.wordsPerMinute ?: 0.0, 0.001)
    }

    @Test
    fun `create session start event includes android client`() {
        val event = RealtimeProtocol.createSessionStart(
            sessionId = "session-123",
            client = "android",
            locale = "en-US",
        )

        assertTrue(event.contains("\"type\":\"session.start\""))
        assertTrue(event.contains("\"client\":\"android\""))
        assertTrue(event.contains("\"locale\":\"en-US\""))
    }
}