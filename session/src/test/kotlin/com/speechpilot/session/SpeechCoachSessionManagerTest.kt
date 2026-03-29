package com.speechpilot.session

import com.speechpilot.audio.AudioCapture
import com.speechpilot.audio.AudioFrame
import com.speechpilot.pace.PaceEstimator
import com.speechpilot.pace.PaceMetrics
import com.speechpilot.segmentation.SpeechSegment
import com.speechpilot.segmentation.SpeechSegmenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
        paceEstimator: PaceEstimator = ConstantPaceEstimator(estimatedWpm = 120.0)
    ): SpeechCoachSessionManager {
        val dispatcher = if (scheduler != null) UnconfinedTestDispatcher(scheduler)
        else UnconfinedTestDispatcher()
        return SpeechCoachSessionManager(
            audioCapture = NoOpAudioCapture(),
            segmenter = segmenter,
            paceEstimator = paceEstimator,
            dispatcher = dispatcher
        )
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────

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

    @Test
    fun `calling stop twice is safe`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        manager.stop()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.stop()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.release()
    }

    @Test
    fun `calling stop without start is safe`() = runTest {
        val manager = buildManager(testScheduler)
        manager.stop()
        assertEquals(SessionState.Idle, manager.state.value)
        manager.release()
    }

    @Test
    fun `start with Active mode stores mode in liveState`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start(SessionMode.Active)
        assertEquals(SessionMode.Active, manager.liveState.value.mode)
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
    fun `default start mode is Active`() = runTest {
        val manager = buildManager(testScheduler)
        manager.start()
        assertEquals(SessionMode.Active, manager.liveState.value.mode)
        manager.release()
    }

    // ── segment processing ─────────────────────────────────────────────────────

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
    fun `processing a segment sets isSpeechDetected`() = runTest {
        val segment = buildSegment(startMs = 0L, endMs = 1_000L)
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(listOf(segment))
        )
        manager.start()
        assertTrue(manager.liveState.value.isSpeechDetected)
        manager.release()
    }

    @Test
    fun `processing a segment updates currentWpm from estimator`() = runTest {
        val segment = buildSegment(startMs = 0L, endMs = 1_000L)
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(listOf(segment)),
            paceEstimator = ConstantPaceEstimator(estimatedWpm = 99.0)
        )
        manager.start()
        assertEquals(99f, manager.liveState.value.currentWpm, 0.01f)
        manager.release()
    }

    @Test
    fun `processing a segment accumulates totalSpeechActiveDurationMs`() = runTest {
        val segment = buildSegment(startMs = 0L, endMs = 2_000L)
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(listOf(segment))
        )
        manager.start()
        assertEquals(2_000L, manager.liveState.value.stats.totalSpeechActiveDurationMs)
        manager.release()
    }

    @Test
    fun `multiple segments accumulate segmentCount and speech duration`() = runTest {
        val segments = listOf(
            buildSegment(startMs = 0L, endMs = 1_000L),
            buildSegment(startMs = 2_000L, endMs = 3_500L)
        )
        val manager = buildManager(
            scheduler = testScheduler,
            segmenter = StubSegmenter(segments)
        )
        manager.start()
        assertEquals(2, manager.liveState.value.stats.segmentCount)
        assertEquals(2_500L, manager.liveState.value.stats.totalSpeechActiveDurationMs)
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
}

// ── test doubles ──────────────────────────────────────────────────────────────

private class NoOpAudioCapture : AudioCapture {
    override val isCapturing: Boolean = false
    override fun frames(): Flow<AudioFrame> = emptyFlow()
    override suspend fun start() {}
    override suspend fun stop() {}
}

/** Emits a fixed list of [SpeechSegment]s regardless of the incoming audio frames. */
private class StubSegmenter(private val segments: List<SpeechSegment>) : SpeechSegmenter {
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> =
        flowOf(*segments.toTypedArray())
}

/** Yields no segments (default no-op case). */
private class NoOpSegmenter : SpeechSegmenter {
    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = emptyFlow()
}

/** Always returns a fixed [PaceMetrics] regardless of segment content. */
private class ConstantPaceEstimator(private val estimatedWpm: Double) : PaceEstimator {
    override fun estimate(segment: SpeechSegment) =
        PaceMetrics(estimatedWpm = estimatedWpm, windowDurationMs = segment.durationMs)

    override fun reset() {}
}

private fun buildSegment(startMs: Long, endMs: Long) = SpeechSegment(
    frames = listOf(AudioFrame(ShortArray(512), 16_000, startMs)),
    startMs = startMs,
    endMs = endMs
)
