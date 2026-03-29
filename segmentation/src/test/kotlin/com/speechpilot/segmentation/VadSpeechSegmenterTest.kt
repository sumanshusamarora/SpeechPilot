package com.speechpilot.segmentation

import com.speechpilot.audio.AudioFrame
import com.speechpilot.vad.VadResult
import com.speechpilot.vad.VoiceActivityDetector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadSpeechSegmenterTest {

    /** VAD stub that returns Speech or Silence based on the provided list of results. */
    private class StubVad(private val results: List<VadResult>) : VoiceActivityDetector {
        private var index = 0
        override fun detect(frame: AudioFrame): VadResult = results[index++]
    }

    private fun frame(ms: Long) = AudioFrame(ShortArray(512) { 0 }, 16_000, ms)

    private fun buildFrames(vararg results: VadResult): Pair<List<AudioFrame>, VoiceActivityDetector> {
        val frames = results.mapIndexed { i, _ -> frame(i.toLong() * 10L) }
        val vad = StubVad(results.toList())
        return Pair(frames, vad)
    }

    @Test
    fun `single speech burst followed by enough silence emits one segment`() = runTest {
        // 3 speech frames then 10 silence frames (>= MIN_SILENCE_FRAMES)
        val (frames, vad) = buildFrames(
            VadResult.Speech, VadResult.Speech, VadResult.Speech,
            *Array(VadSpeechSegmenter.MIN_SILENCE_FRAMES) { VadResult.Silence }
        )
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = VadSpeechSegmenter.MIN_SILENCE_FRAMES)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertEquals(1, segments.size)
        assertEquals(3, segments[0].frames.size)
    }

    @Test
    fun `incomplete silence gap does not prematurely flush segment`() = runTest {
        // 2 speech, 3 silence (less than MIN_SILENCE_FRAMES=10), 2 more speech, then enough silence
        val silenceCount = VadSpeechSegmenter.MIN_SILENCE_FRAMES
        val (frames, vad) = buildFrames(
            VadResult.Speech, VadResult.Speech,
            VadResult.Silence, VadResult.Silence, VadResult.Silence,
            VadResult.Speech, VadResult.Speech,
            *Array(silenceCount) { VadResult.Silence }
        )
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = silenceCount)
        val segments = segmenter.segment(frames.asFlow()).toList()

        // All speech frames should end up in one segment (gap was too short to flush)
        assertEquals(1, segments.size)
        assertEquals(4, segments[0].frames.size)
    }

    @Test
    fun `two speech bursts separated by enough silence emit two segments`() = runTest {
        val silenceCount = VadSpeechSegmenter.MIN_SILENCE_FRAMES
        val (frames, vad) = buildFrames(
            VadResult.Speech, VadResult.Speech,
            *Array(silenceCount) { VadResult.Silence },
            VadResult.Speech, VadResult.Speech, VadResult.Speech,
            *Array(silenceCount) { VadResult.Silence }
        )
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = silenceCount)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertEquals(2, segments.size)
        assertEquals(2, segments[0].frames.size)
        assertEquals(3, segments[1].frames.size)
    }

    @Test
    fun `trailing speech frames with no final silence are flushed as segment`() = runTest {
        val (frames, vad) = buildFrames(
            VadResult.Speech, VadResult.Speech, VadResult.Speech
        )
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = VadSpeechSegmenter.MIN_SILENCE_FRAMES)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertEquals(1, segments.size)
        assertEquals(3, segments[0].frames.size)
    }

    @Test
    fun `all silence frames produce no segments`() = runTest {
        val (frames, vad) = buildFrames(
            *Array(20) { VadResult.Silence }
        )
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = VadSpeechSegmenter.MIN_SILENCE_FRAMES)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `empty frame flow produces no segments`() = runTest {
        val vad = StubVad(emptyList())
        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = VadSpeechSegmenter.MIN_SILENCE_FRAMES)
        val segments = segmenter.segment(emptyList<AudioFrame>().asFlow()).toList()

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `segment start and end timestamps are set from frames`() = runTest {
        val silenceCount = VadSpeechSegmenter.MIN_SILENCE_FRAMES
        val speechResults = listOf(VadResult.Speech, VadResult.Speech)
        val silenceResults = List(silenceCount) { VadResult.Silence }
        val allResults = speechResults + silenceResults
        val frames = allResults.mapIndexed { i, _ -> frame(i.toLong() * 100L) }
        val vad = StubVad(allResults)

        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = silenceCount)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertEquals(1, segments.size)
        val segment = segments[0]
        // startMs should match the first speech frame
        assertEquals(0L, segment.startMs)
        // endMs should match the timestamp of the frame that triggered the flush
        val expectedEndMs = frames[speechResults.size + silenceCount - 1].capturedAtMs
        assertEquals(expectedEndMs, segment.endMs)
    }

    @Test
    fun `segment durationMs reflects start to end span`() = runTest {
        val silenceCount = VadSpeechSegmenter.MIN_SILENCE_FRAMES
        val allResults = listOf(VadResult.Speech) + List(silenceCount) { VadResult.Silence }
        val frames = allResults.mapIndexed { i, _ -> frame(i.toLong() * 50L) }
        val vad = StubVad(allResults)

        val segmenter = VadSpeechSegmenter(vad, minSilenceFrames = silenceCount)
        val segments = segmenter.segment(frames.asFlow()).toList()

        assertEquals(1, segments.size)
        val segment = segments[0]
        assertEquals(segment.endMs - segment.startMs, segment.durationMs)
    }
}
