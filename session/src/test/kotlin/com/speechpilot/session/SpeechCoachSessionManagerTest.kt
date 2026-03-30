package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import com.speechpilot.pace.PaceEstimator
import com.speechpilot.pace.PaceMetrics
import com.speechpilot.segmentation.SpeechSegment
import com.speechpilot.segmentation.SpeechSegmenter
import com.speechpilot.transcription.LocalTranscriber
import com.speechpilot.transcription.TranscriptStability
import com.speechpilot.transcription.TranscriptUpdate
import com.speechpilot.transcription.TranscriptionEngineStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        transcriber: LocalTranscriber = NoOpTestTranscriber()
    ): SpeechCoachSessionManager {
        val dispatcher = if (scheduler != null) UnconfinedTestDispatcher(scheduler)
        else UnconfinedTestDispatcher()
        return SpeechCoachSessionManager(
            audioCapture = NoOpAudioCapture(),
            segmenter = segmenter,
            paceEstimator = paceEstimator,
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
        assertEquals(SessionState.Active, manager.state.value)
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
    fun `start with Passive mode stores mode in liveState`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start(SessionMode.Passive)
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
        assertEquals(150.0, manager.liveState.value.stats.peakEstimatedWpm, 0.001)
        manager.release()
    }

    @Test
    fun `transcript final update is reflected in live state`() = runTest {
        val transcriber = FakeTranscriber()
        val manager = buildManager(scheduler = testScheduler, transcriber = transcriber)

        manager.start()
        transcriber.emitFinal("hello world", atMs = 5_000L)

        assertTrue(manager.liveState.value.transcriptText.contains("hello world"))
        assertTrue(manager.liveState.value.transcriptRollingWpm > 0f)
        manager.release()
    }

    @Test
    fun `no-op transcriber keeps transcript metrics at zero`() = runTest {
        val manager = buildManager(scheduler = testScheduler, transcriber = NoOpTestTranscriber())
        manager.start()

        assertEquals("", manager.liveState.value.transcriptText)
        assertEquals(0f, manager.liveState.value.transcriptRollingWpm, 0.001f)
        manager.release()
    }

    @Test
    fun `mic level and speech active update when high-energy frames arrive`() = runTest {
        // Samples with value 500 produce RMS = 500, which is above VAD_SPEECH_THRESHOLD (300)
        // and normalises to 500 / 5000 = 0.1 micLevel.
        val frame = AudioFrame(ShortArray(512) { 500 }, 16_000, 0L)
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

        assertEquals(0f, manager.liveState.value.micLevel, 0.001f)
        assertFalse(manager.liveState.value.isSpeechActive)
        manager.release()
    }

    @Test
    fun `isSpeechActive is false for low-energy silent frames`() = runTest {
        // Samples with value 10 produce RMS ≈ 10, well below VAD_SPEECH_THRESHOLD (300).
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
        assertTrue(manager.liveState.value.isSpeechDetected)
        // isSpeechDetected must persist — it is the historical "speech seen" flag.
        assertTrue(manager.liveState.value.isSpeechDetected)
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
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> =
        flowOf(*segments.toTypedArray())
}

private class NoOpSegmenter : SpeechSegmenter {
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = emptyFlow()
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

    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}

private class FakeTranscriber : LocalTranscriber {
    private val flow = MutableSharedFlow<TranscriptUpdate>(extraBufferCapacity = 8)
    private val statusFlow = MutableStateFlow(TranscriptionEngineStatus.Disabled)

    override val updates: Flow<TranscriptUpdate> = flow
    override val status: StateFlow<TranscriptionEngineStatus> = statusFlow

    override suspend fun start() {
        statusFlow.value = TranscriptionEngineStatus.Listening
    }

    override suspend fun stop() {
        statusFlow.value = TranscriptionEngineStatus.Disabled
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
}

private fun buildSegment(startMs: Long, endMs: Long) = SpeechSegment(
    frames = listOf(AudioFrame(ShortArray(512), 16_000, startMs)),
    startMs = startMs,
    endMs = endMs
)
