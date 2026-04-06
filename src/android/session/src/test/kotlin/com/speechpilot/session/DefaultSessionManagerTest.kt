package com.speechpilot.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DefaultSessionManagerTest {

    private lateinit var manager: DefaultSessionManager

    @Before
    fun setUp() {
        manager = DefaultSessionManager()
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(SessionState.Idle, manager.state.value)
    }

    @Test
    fun `start transitions to Active`() = runTest {
        manager.start()
        assertEquals(SessionState.Active, manager.state.value)
    }

    @Test
    fun `stop after start transitions back to Idle`() = runTest {
        manager.start()
        manager.stop()
        assertEquals(SessionState.Idle, manager.state.value)
    }
}
