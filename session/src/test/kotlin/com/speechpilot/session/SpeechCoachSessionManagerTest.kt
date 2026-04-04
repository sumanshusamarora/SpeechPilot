package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import com.speechpilot.feedback.ThresholdFeedbackDecision
import com.speechpilot.feedback.FeedbackDecision
import com.speechpilot.pace.PaceEstimator
import com.speechpilot.pace.PaceMetrics
import com.speechpilot.segmentation.SpeechSegment
import com.speechpilot.segmentation.SpeechSegmenter
import com.speechpilot.segmentation.VadSpeechSegmenter
import com.speechpilot.transcription.LocalTranscriber
import com.speechpilot.transcription.TranscriptionBackend
import com.speechpilot.transcription.TranscriptStability
import com.speechpilot.transcription.TranscriptionDiagnostics
import com.speechpilot.transcription.TranscriptionFailure
import com.speechpilot.transcription.TranscriptUpdate
import com.speechpilot.transcription.TranscriptionEngineStatus
import com.speechpilot.vad.EnergyBasedVad
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechCoachSessionManagerTest {

    private fun buildManager(
        scheduler: TestCoroutineScheduler? = null,
        segmenter: SpeechSegmenter = NoOpSegmenter(),
        paceEstimator: PaceEstimator = ConstantPaceEstimator(estimatedWpm = 120.0),
        transcriber: LocalTranscriber = NoOpTestTranscriber(),
        feedbackDecision: FeedbackDecision = ThresholdFeedbackDecision()
    ): SpeechCoachSessionManager {
        val dispatcher = if (scheduler != null) UnconfinedTestDispatcher(scheduler)
        else UnconfinedTestDispatcher()
        return SpeechCoachSessionManager(
            audioCapture = NoOpAudioCapture(),
            segmenter = segmenter,
            paceEstimator = paceEstimator,
            feedbackDecision = feedbackDecision,
            localTranscriber = transcriber,
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
        advanceUntilIdle()
        assertEquals(SessionState.Active, manager.state.value)
        manager.release()
    }

    @Test
    fun `stop after start returns to Idle`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        advanceUntilIdle()
        manager.stop()
        advanceUntilIdle()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.release()
    }

    @Test
    fun `start with Passive mode stores mode in liveState`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start(SessionMode.Passive)
        advanceUntilIdle()
        assertEquals(SessionMode.Passive, manager.liveState.value.mode)
        manager.release()
    }

    @Test
    fun `processing a segment increments segmentCount`() = runTest {
        val segment = buildSegment(startMs = 0L, endMs = 1_000L)
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(listOf(segment))
        )
        manager.start()
        advanceUntilIdle()
        assertEquals(1, manager.liveState.value.stats.segmentCount)
        manager.release()
    }

    @Test
    fun `peak wpm reflects maximum observed value`() = runTest {
        var callIndex = 0
        val wpmValues = listOf(50.0, 150.0, 80.0)
        val segments = wpmValues.mapIndexed { i, _ ->
            buildSegment(startMs = i * 1000L, endMs = (i + 1) * 1000L)
        }
        val estimator = object : PaceEstimator {
            override fun estimate(segment: SpeechSegment): PaceMetrics {
                val wpm = wpmValues[callIndex++]
                return PaceMetrics(estimatedWpm = wpm, windowDurationMs = segment.durationMs)
            }
            override fun reset() { callIndex = 0 }
        }
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(segments),
            paceEstimator = estimator
        )
        manager.start()
        advanceUntilIdle()
        assertEquals(150.0, manager.liveState.value.stats.peakEstimatedWpm, 0.001)
        manager.release()
    }

    @Test
    fun `transcript final update is reflected in live state`() = runTest {
        val transcriber = FakeTranscriber()
        val manager = buildManager(scheduler = testScheduler, transcriber = transcriber)

        manager.start()
        advanceUntilIdle()
        transcriber.emitFinal("hello world", atMs = 5_000L)
        advanceUntilIdle()

        assertTrue(manager.liveState.value.transcriptDebug.transcriptText.contains("hello world"))
        assertTrue(manager.liveState.value.transcriptDebug.rollingWpm > 0f)
        manager.release()
    }

    @Test
    fun `no-op transcriber keeps transcript metrics at zero`() = runTest {
        val manager = buildManager(scheduler = testScheduler, transcriber = NoOpTestTranscriber())
        manager.start()
        advanceUntilIdle()

        assertEquals("", manager.liveState.value.transcriptDebug.transcriptText)
        assertEquals(0f, manager.liveState.value.transcriptDebug.rollingWpm, 0.001f)
        manager.release()
    }

    @Test
    fun `partial transcript keeps wpm pending without finalized words`() = runTest {
        val transcriber = FakeTranscriber()
        val manager = buildManager(scheduler = testScheduler, transcriber = transcriber)

        manager.start()
        advanceUntilIdle()
        transcriber.emitPartial("hello there", atMs = 2_000L)
        advanceUntilIdle()

        assertTrue(manager.liveState.value.transcriptDebug.partialTranscriptPresent)
        assertTrue(manager.liveState.value.transcriptDebug.wpmPendingFinalRecognition)
        assertEquals(0f, manager.liveState.value.transcriptDebug.rollingWpm, 0.001f)
        manager.release()
    }

    @Test
    fun `transcription diagnostics are mirrored into live state`() = runTest {
        val transcriber = FakeTranscriber()
        val manager = buildManager(scheduler = testScheduler, transcriber = transcriber)

        manager.start()
        advanceUntilIdle()
        transcriber.updateDiagnostics(
            TranscriptionDiagnostics(
                selectedBackend = TranscriptionBackend.WhisperCpp,
                activeBackend = TranscriptionBackend.AndroidSpeechRecognizer,
                selectedBackendStatus = TranscriptionEngineStatus.NativeLibraryUnavailable,
                activeBackendStatus = TranscriptionEngineStatus.Listening,
                fallbackActive = true,
                fallbackReason = TranscriptionFailure(
                    code = "primary-native-library-unavailable",
                    message = "Whisper native library failed to load — using Android fallback",
                ),
                modelPath = "/tmp/whisper/ggml-small.bin",
                modelFilePresent = true,
                nativeLibraryName = "whisper_jni",
                nativeLibraryLoaded = false,
                nativeLibraryLoadError = "dlopen failed",
                selectedBackendInitSucceeded = false,
                audioSourceAttached = true,
                selectedBackendAudioFramesReceived = 12,
                selectedBackendBufferedSamples = 16000,
                chunksProcessed = 1,
                fallbackTranscriptUpdatesEmitted = 2,
                totalTranscriptUpdatesEmitted = 2,
                lastTranscriptSource = TranscriptionBackend.AndroidSpeechRecognizer,
                lastTranscriptError = TranscriptionFailure(
                    code = "primary-native-library-unavailable",
                    message = "Whisper native library failed to load — using Android fallback",
                ),
                lastSuccessfulTranscriptAtMs = 1_000L,
            )
        )
        advanceUntilIdle()

        val diagnostics = manager.liveState.value.transcriptDebug.diagnostics
        assertEquals(TranscriptionBackend.WhisperCpp, diagnostics.selectedBackend)
        assertEquals(TranscriptionBackend.AndroidSpeechRecognizer, diagnostics.activeBackend)
        assertTrue(diagnostics.fallbackActive)
        assertEquals("primary-native-library-unavailable", diagnostics.fallbackReason?.code)
        assertEquals(12, diagnostics.selectedBackendAudioFramesReceived)
        manager.release()
    }

    @Test
    fun `mic level and speech active update when high-energy frames arrive`() = runTest {
        // Samples with value 1_000 produce RMS = 1_000, above default VAD threshold (750)
        // and normalises to 1_000 / 5_000 = 0.2 micLevel.
        val frame = AudioFrame(ShortArray(512) { 1_000 }, 16_000, 0L)
        // Emit FRAME_LEVEL_UPDATE_INTERVAL frames to trigger a state update.
        val frames = flow {
            repeat(SpeechCoachSessionManager.FRAME_LEVEL_UPDATE_INTERVAL) { emit(frame) }
        }
        val manager = SpeechCoachSessionManager(
            audioCapture = StubAudioCapture(frames),
            segmenter = NoOpSegmenter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        manager.start()
        advanceUntilIdle()

        assertTrue("micLevel should be > 0 after high-energy frames",
            manager.liveState.value.micLevel > 0f)
        assertTrue("isSpeechActive should be true when RMS exceeds threshold",
            manager.liveState.value.isSpeechActive)
        manager.release()
    }

    @Test
    fun `mic level stays zero and speech inactive when no audio frames emitted`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        advanceUntilIdle()

        assertEquals(0f, manager.liveState.value.micLevel, 0.001f)
        assertFalse(manager.liveState.value.isSpeechActive)
        manager.release()
    }

    @Test
    fun `isSpeechActive is false for low-energy silent frames`() = runTest {
        // Samples with value 10 produce RMS ≈ 10, well below default VAD threshold (750).
        val frame = AudioFrame(ShortArray(512) { 10 }, 16_000, 0L)
        val frames = flow {
            repeat(SpeechCoachSessionManager.FRAME_LEVEL_UPDATE_INTERVAL) { emit(frame) }
        }
        val manager = SpeechCoachSessionManager(
            audioCapture = StubAudioCapture(frames),
            segmenter = NoOpSegmenter(),
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        manager.start()
        advanceUntilIdle()

        assertFalse("isSpeechActive must be false for low-energy (silent) frames",
            manager.liveState.value.isSpeechActive)
        manager.release()
    }

    @Test
    fun `isSpeechDetected remains true for session lifetime after first speech segment`() = runTest {
        val segment = buildSegment(startMs = 0L, endMs = 1_000L)
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(listOf(segment))
        )
        manager.start()
        advanceUntilIdle()
        assertTrue(manager.liveState.value.isSpeechDetected)
        // isSpeechDetected must persist — it is the historical "speech seen" flag.
        assertTrue(manager.liveState.value.isSpeechDetected)
        manager.release()
    }

    @Test
    fun `debug target and vad threshold are populated immediately on session start`() = runTest {
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = VadSpeechSegmenter(EnergyBasedVad())
        )

        manager.start()

        val debugInfo = manager.liveState.value.debugInfo
        assertEquals(ThresholdFeedbackDecision.TARGET_WPM, debugInfo.targetWpm, 0.001)
        assertEquals(EnergyBasedVad.DEFAULT_THRESHOLD, debugInfo.vadThreshold, 0.001)
        manager.release()
    }

    @Test
    fun `segmentation debug reports finalized segments when speech followed by silence`() = runTest {
        val speechFrame = AudioFrame(ShortArray(512) { 1_200 }, 16_000, 0L)
        val silenceFrame = AudioFrame(ShortArray(512) { 0 }, 16_000, 0L)
        val frameFlow = flow {
            repeat(4) { emit(speechFrame.copy(capturedAtMs = it * 32L)) }
            repeat(VadSpeechSegmenter.MIN_SILENCE_FRAMES) {
                emit(silenceFrame.copy(capturedAtMs = (4 + it) * 32L))
            }
        }
        val manager = SpeechCoachSessionManager(
            audioCapture = StubAudioCapture(frameFlow),
            segmenter = VadSpeechSegmenter(EnergyBasedVad()),
            dispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        manager.start()

        assertTrue(manager.liveState.value.stats.segmentCount > 0)
        assertEquals(
            manager.liveState.value.stats.segmentCount,
            manager.liveState.value.debugInfo.finalizedSegmentsCount
        )
        manager.release()
    }
}

private class NoOpAudioCapture : AudioCapture {
    override val isCapturing: Boolean = false
    override fun frames(): Flow<AudioFrame> = emptyFlow()
    override suspend fun start() {}
    override suspend fun stop() {}
}

private class StubAudioCapture(private val frameFlow: Flow<AudioFrame>) : AudioCapture {
    override val isCapturing: Boolean = false
    override fun frames(): Flow<AudioFrame> = frameFlow
    override suspend fun start() {}
    override suspend fun stop() {}
}

private class StubSegmenter(private val segments: List<SpeechSegment>) : SpeechSegmenter {
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = flow {
        segments.forEach { emit(it) }
        awaitCancellation()
    }
}

private class NoOpSegmenter : SpeechSegmenter {
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = flow {
        awaitCancellation()
    }
}

private class ConstantPaceEstimator(private val estimatedWpm: Double) : PaceEstimator {
    override fun estimate(segment: SpeechSegment) =
        PaceMetrics(estimatedWpm = estimatedWpm, windowDurationMs = segment.durationMs)

    override fun reset() {}
}


private class NoOpTestTranscriber : LocalTranscriber {
    override val updates: Flow<TranscriptUpdate> = emptyFlow()
    override val status: StateFlow<TranscriptionEngineStatus> =
        MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val diagnostics: StateFlow<TranscriptionDiagnostics> =
        MutableStateFlow(TranscriptionDiagnostics())
    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(TranscriptionBackend.None)

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}

private class FakeTranscriber : LocalTranscriber {
    private val flow = MutableSharedFlow<TranscriptUpdate>(extraBufferCapacity = 8)
    private val statusFlow = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    private val diagnosticsFlow = MutableStateFlow(
        TranscriptionDiagnostics(
            selectedBackend = TranscriptionBackend.AndroidSpeechRecognizer,
            activeBackend = TranscriptionBackend.AndroidSpeechRecognizer,
        )
    )

    override val updates: Flow<TranscriptUpdate> = flow
    override val status: StateFlow<TranscriptionEngineStatus> = statusFlow
    override val diagnostics: StateFlow<TranscriptionDiagnostics> = diagnosticsFlow
    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(TranscriptionBackend.AndroidSpeechRecognizer)

    override suspend fun start() {
        statusFlow.value = TranscriptionEngineStatus.Listening
        diagnosticsFlow.value = diagnosticsFlow.value.copy(
            selectedBackendStatus = TranscriptionEngineStatus.Listening,
            activeBackendStatus = TranscriptionEngineStatus.Listening,
            selectedBackendInitSucceeded = true,
        )
    }

    override suspend fun stop() {
        statusFlow.value = TranscriptionEngineStatus.Disabled
        diagnosticsFlow.value = diagnosticsFlow.value.copy(
            selectedBackendStatus = TranscriptionEngineStatus.Disabled,
            activeBackendStatus = TranscriptionEngineStatus.Disabled,
        )
    }

    fun updateDiagnostics(diagnostics: TranscriptionDiagnostics) {
        diagnosticsFlow.value = diagnostics
    }

    fun emitFinal(text: String, atMs: Long) {
        flow.tryEmit(
            TranscriptUpdate(
                text = text,
                stability = TranscriptStability.Final,
                receivedAtMs = atMs
            )
        )
    }

    fun emitPartial(text: String, atMs: Long) {
        flow.tryEmit(
            TranscriptUpdate(
                text = text,
                stability = TranscriptStability.Partial,
                receivedAtMs = atMs
            )
        )
    }
}

private fun buildSegment(startMs: Long, endMs: Long) = SpeechSegment(
    frames = listOf(AudioFrame(ShortArray(512), 16_000, startMs)),
    startMs = startMs,
    endMs = endMs
)
