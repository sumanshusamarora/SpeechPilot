package com.speechpilot.transcription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@ExperimentalCoroutinesApi
class RoutingLocalTranscriberTest {

    // -------------------------------------------------------------------------------------
    // Stub helpers
    // -------------------------------------------------------------------------------------

    private class StubTranscriber(
        initialStatus: TranscriptionEngineStatus,
        override val activeBackend: StateFlow<TranscriptionBackend>
    ) : LocalTranscriber {
        override val updates: Flow<TranscriptUpdate> = emptyFlow()
        val _status = MutableStateFlow(initialStatus)
        override val status: StateFlow<TranscriptionEngineStatus> = _status
        private val _diagnostics = MutableStateFlow(
            TranscriptionDiagnostics(
                selectedBackend = activeBackend.value,
                activeBackend = activeBackend.value,
                selectedBackendStatus = initialStatus,
                activeBackendStatus = initialStatus,
                modelPath = "/tmp/model",
                modelFilePresent = true,
            )
        )
        override val diagnostics: StateFlow<TranscriptionDiagnostics> = _diagnostics

        var started = false
        var stopped = false

        override suspend fun start() {
            started = true
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = _status.value,
                activeBackendStatus = _status.value,
            )
        }

        override suspend fun stop() {
            stopped = true
            _status.value = TranscriptionEngineStatus.Disabled
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = TranscriptionEngineStatus.Disabled,
                activeBackendStatus = TranscriptionEngineStatus.Disabled,
            )
        }
    }

    private fun makePrimary(
        initialStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Listening
    ) = StubTranscriber(
        initialStatus = initialStatus,
        activeBackend = MutableStateFlow(TranscriptionBackend.DedicatedLocalStt)
    )

    private fun makeFallback(
        initialStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Listening
    ) = StubTranscriber(
        initialStatus = initialStatus,
        activeBackend = MutableStateFlow(TranscriptionBackend.AndroidSpeechRecognizer)
    )

    // -------------------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------------------

    @Test
    fun `uses primary backend when it starts successfully`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.Listening)
        val fallback = makeFallback()

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)

        assertEquals(TranscriptionBackend.DedicatedLocalStt, router.activeBackend.value)
        assertEquals(true, primary.started)
        assertEquals(false, fallback.started)
    }

    @Test
    fun `falls back to Android recognizer when primary reports ModelUnavailable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.ModelUnavailable)
        val fallback = makeFallback(TranscriptionEngineStatus.Listening)

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)

        assertEquals(TranscriptionBackend.AndroidSpeechRecognizer, router.activeBackend.value)
        assertEquals(true, primary.started)
        assertEquals(true, primary.stopped)
        assertEquals(true, fallback.started)
    }

    @Test
    fun `diagnostics preserve selected backend and explicit fallback reason`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.NativeLibraryUnavailable)
        val fallback = makeFallback(TranscriptionEngineStatus.Listening)

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)

        assertEquals(TranscriptionBackend.DedicatedLocalStt, router.diagnostics.value.selectedBackend)
        assertEquals(TranscriptionBackend.AndroidSpeechRecognizer, router.diagnostics.value.activeBackend)
        assertEquals(true, router.diagnostics.value.fallbackActive)
        assertEquals(
            "primary-native-library-unavailable",
            router.diagnostics.value.fallbackReason?.code
        )
    }

    @Test
    fun `falls back to Android recognizer when primary reports NativeLibraryUnavailable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.NativeLibraryUnavailable)
        val fallback = makeFallback(TranscriptionEngineStatus.Listening)

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)

        assertEquals(TranscriptionBackend.AndroidSpeechRecognizer, router.activeBackend.value)
        assertEquals(true, primary.started)
        assertEquals(true, primary.stopped)
        assertEquals(true, fallback.started)
    }

    @Test
    fun `active backend is None before start`() {
        val router = RoutingLocalTranscriber(
            primaryTranscriber = makePrimary(),
            fallbackTranscriber = makeFallback()
        )

        assertEquals(TranscriptionBackend.None, router.activeBackend.value)
        assertEquals(TranscriptionEngineStatus.Disabled, router.status.value)
    }

    @Test
    fun `active backend resets to None after stop`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.Listening)
        val fallback = makeFallback()

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)
        router.stop()

        assertEquals(TranscriptionBackend.None, router.activeBackend.value)
        assertEquals(TranscriptionEngineStatus.Disabled, router.status.value)
    }

    @Test
    fun `second start call is ignored when already running`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val primary = makePrimary(TranscriptionEngineStatus.Listening)
        val fallback = makeFallback()

        val router = RoutingLocalTranscriber(
            primaryTranscriber = primary,
            fallbackTranscriber = fallback,
            fallbackDelayMs = 100,
            dispatcher = testDispatcher
        )

        router.start()
        advanceTimeBy(500)
        router.start()
        advanceTimeBy(200)

        // Primary should still only have been started once.
        assertEquals(TranscriptionBackend.DedicatedLocalStt, router.activeBackend.value)
    }
}
