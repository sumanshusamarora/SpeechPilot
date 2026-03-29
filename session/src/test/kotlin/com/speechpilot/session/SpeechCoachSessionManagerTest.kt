package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechCoachSessionManagerTest {

    private fun buildManager(scheduler: TestCoroutineScheduler? = null): SpeechCoachSessionManager {
        val dispatcher = if (scheduler != null) UnconfinedTestDispatcher(scheduler) else UnconfinedTestDispatcher()
        return SpeechCoachSessionManager(
            audioCapture = NoOpAudioCapture(),
            dispatcher = dispatcher
        )
    }

    @Test
    fun `initial state is Idle`() {
        val manager = buildManager()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.release()
    }

    @Test
    fun `initial liveState has defaults`() {
        val manager = buildManager()
        assertEquals(SessionState.Idle, manager.liveState.value.sessionState)
        assertFalse(manager.liveState.value.isListening)
        manager.release()
    }

    @Test
    fun `start transitions to Active`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        assertEquals(SessionState.Active, manager.state.value)
        manager.release()
    }

    @Test
    fun `start sets isListening true`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        assertTrue(manager.liveState.value.isListening)
        manager.release()
    }

    @Test
    fun `stop after start returns to Idle`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        manager.stop()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.release()
    }

    @Test
    fun `stop clears liveState`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        manager.stop()
        assertFalse(manager.liveState.value.isListening)
        assertEquals(SessionState.Idle, manager.liveState.value.sessionState)
        manager.release()
    }

    @Test
    fun `calling start twice does not change state`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        assertEquals(SessionState.Active, manager.state.value)
        manager.start()
        assertEquals(SessionState.Active, manager.state.value)
        manager.release()
    }
}

private class NoOpAudioCapture : AudioCapture {
    override val isCapturing: Boolean = false
    override fun frames(): Flow<AudioFrame> = emptyFlow()
    override suspend fun start() {}
    override suspend fun stop() {}
}

