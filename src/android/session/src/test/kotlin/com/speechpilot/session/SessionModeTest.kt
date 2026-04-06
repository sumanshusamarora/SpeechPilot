package com.speechpilot.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionModeTest {

    @Test
    fun `Active and Passive are distinct values`() {
        assertNotEquals(SessionMode.Active, SessionMode.Passive)
    }

    @Test
    fun `default LiveSessionState mode is Active`() {
        val state = LiveSessionState()
        assertEquals(SessionMode.Active, state.mode)
    }

    @Test
    fun `LiveSessionState copy preserves Passive mode`() {
        val state = LiveSessionState(mode = SessionMode.Passive)
        val copy = state.copy(isListening = true)
        assertEquals(SessionMode.Passive, copy.mode)
    }

    @Test
    fun `LiveSessionState with Passive mode can be distinguished from Active`() {
        val active = LiveSessionState(mode = SessionMode.Active)
        val passive = LiveSessionState(mode = SessionMode.Passive)
        assertNotEquals(active, passive)
    }

    @Test
    fun `SessionMode values covers both expected variants`() {
        val values = SessionMode.entries
        assertEquals(2, values.size)
        assert(SessionMode.Active in values)
        assert(SessionMode.Passive in values)
    }
}
