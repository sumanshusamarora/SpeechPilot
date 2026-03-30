package com.speechpilot.pace

import com.speechpilot.audio.AudioFrame
import com.speechpilot.segmentation.SpeechSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class RollingWindowPaceEstimatorTest {

    private lateinit var estimator: RollingWindowPaceEstimator

    @Before
    fun setUp() {
        estimator = RollingWindowPaceEstimator(windowMs = 60_000L)
    }

    // ── basic output shape ─────────────────────────────────────────────────────

    @Test
    fun `zero-duration segment returns zero estimatedWpm`() {
        val segment = SpeechSegment(
            frames = listOf(AudioFrame(ShortArray(0), 16_000, 0L)),
            startMs = 0L,
            endMs = 0L
        )
        val metrics = estimator.estimate(segment)
        assertEquals(0.0, metrics.estimatedWpm, 0.001)
        assertEquals(0L, metrics.windowDurationMs)
    }

    @Test
    fun `non-zero duration segment returns positive estimatedWpm`() {
        val segment = segmentWithSyllables(durationMs = 2_000L, syllablesPerSecond = 3.0, startMs = 0L)
        val metrics = estimator.estimate(segment)
        assertTrue(metrics.estimatedWpm > 0.0)
    }

    @Test
    fun `windowDurationMs equals segment duration for single segment`() {
        val segment = segmentWithSyllables(durationMs = 3_000L, syllablesPerSecond = 3.0, startMs = 0L)
        val metrics = estimator.estimate(segment)
        assertEquals(3_000L, metrics.windowDurationMs)
    }

    // ── rolling window accumulation ────────────────────────────────────────────

    @Test
    fun `two segments within window accumulate windowDurationMs`() {
        estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 0L))
        val metrics = estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 1_500L))
        assertEquals(2_000L, metrics.windowDurationMs) // two 1000ms segments
    }

    @Test
    fun `segment outside window is evicted`() {
        // Add a segment that starts 65 s before the latest segment end – outside 60 s window
        estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 0L))
        val metrics = estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 65_000L))
        // Only the recent segment should remain in window
        assertEquals(1_000L, metrics.windowDurationMs)
    }

    // ── reset behaviour ────────────────────────────────────────────────────────

    @Test
    fun `reset clears internal state`() {
        estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 0L))
        estimator.reset()
        val afterReset = estimator.estimate(segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 0L))
        // After reset window contains only the single new segment
        assertEquals(1_000L, afterReset.windowDurationMs)
    }

    @Test
    fun `reset then zero-duration segment returns zero`() {
        estimator.estimate(segmentWithSyllables(durationMs = 5_000L, syllablesPerSecond = 3.0, startMs = 0L))
        estimator.reset()
        val metrics = estimator.estimate(SpeechSegment(
            frames = listOf(AudioFrame(ShortArray(0), 16_000, 0L)),
            startMs = 0L,
            endMs = 0L
        ))
        assertEquals(0.0, metrics.estimatedWpm, 0.001)
    }

    // ── syllable-rate proxy correctness ───────────────────────────────────────

    @Test
    fun `higher syllable rate produces higher estimated wpm for same duration`() {
        val slowEstimator = RollingWindowPaceEstimator(windowMs = 300_000L)
        val fastEstimator = RollingWindowPaceEstimator(windowMs = 300_000L)

        // Slow: 2 syllables/second over 5 seconds
        val slowSegment = segmentWithSyllables(durationMs = 5_000L, syllablesPerSecond = 2.0, startMs = 0L)
        // Fast: 5 syllables/second over 5 seconds
        val fastSegment = segmentWithSyllables(durationMs = 5_000L, syllablesPerSecond = 5.0, startMs = 0L)

        val slowMetrics = slowEstimator.estimate(slowSegment)
        val fastMetrics = fastEstimator.estimate(fastSegment)

        assertTrue(
            "Fast speech (5 syl/s) should produce higher est-WPM than slow speech (2 syl/s); " +
                "got fast=${fastMetrics.estimatedWpm}, slow=${slowMetrics.estimatedWpm}",
            fastMetrics.estimatedWpm > slowMetrics.estimatedWpm
        )
    }

    @Test
    fun `estimated wpm scales with syllable rate in single long segment`() {
        // Build three estimators with different syllable rates in same 10-second speech window
        val rates = listOf(1.5, 3.0, 5.0) // syllables per second
        val wpmValues = rates.map { rate ->
            val est = RollingWindowPaceEstimator(windowMs = 60_000L)
            est.estimate(segmentWithSyllables(durationMs = 10_000L, syllablesPerSecond = rate, startMs = 0L))
                .estimatedWpm
        }

        // Each faster rate should produce higher WPM
        assertTrue("1.5 syl/s < 3.0 syl/s in WPM", wpmValues[0] < wpmValues[1])
        assertTrue("3.0 syl/s < 5.0 syl/s in WPM", wpmValues[1] < wpmValues[2])
    }

    // ── syllable proxy counting ────────────────────────────────────────────────

    @Test
    fun `countSyllableProxies returns at least 1 for non-silent segment`() {
        val seg = segmentWithSyllables(durationMs = 1_000L, syllablesPerSecond = 3.0, startMs = 0L)
        assertTrue(estimator.countSyllableProxies(seg) >= 1)
    }

    @Test
    fun `countSyllableProxies returns 0 for segment with no audio frames`() {
        val empty = SpeechSegment(frames = emptyList(), startMs = 0L, endMs = 1_000L)
        assertEquals(0, estimator.countSyllableProxies(empty))
    }

    @Test
    fun `countSyllableProxies is higher for faster syllable rate`() {
        val slowSeg = segmentWithSyllables(durationMs = 5_000L, syllablesPerSecond = 2.0, startMs = 0L)
        val fastSeg = segmentWithSyllables(durationMs = 5_000L, syllablesPerSecond = 5.0, startMs = 0L)

        val slowCount = estimator.countSyllableProxies(slowSeg)
        val fastCount = estimator.countSyllableProxies(fastSeg)

        assertTrue(
            "Fast segment should have more syllable proxies; got fast=$fastCount, slow=$slowCount",
            fastCount > slowCount
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a [SpeechSegment] whose audio frames simulate speech at [syllablesPerSecond].
     *
     * The amplitude envelope oscillates at the target syllable frequency using a sinusoidal
     * carrier wave. This creates energy peaks that [RollingWindowPaceEstimator.countSyllableProxies]
     * can detect and count. The carrier amplitude (peak ≈ 3000, trough ≈ 1000) is always
     * above the [EnergyBasedVad][com.speechpilot.vad.EnergyBasedVad] threshold of 300 RMS,
     * ensuring the full segment would be classified as speech in a full pipeline run.
     *
     * Frame size matches [MicrophoneCapture.FRAME_SIZE][com.speechpilot.audio.MicrophoneCapture] (512 samples at 16 kHz ≈ 32 ms/frame).
     */
    private fun segmentWithSyllables(
        durationMs: Long,
        syllablesPerSecond: Double,
        startMs: Long,
        sampleRate: Int = 16_000,
        frameSize: Int = 512
    ): SpeechSegment {
        val totalSamples = (durationMs * sampleRate / 1_000L).toInt()
        val pcm = ShortArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            // Envelope: oscillates between 0.0 and 1.0 at syllablesPerSecond
            val envelope = (sin(2.0 * PI * syllablesPerSecond * t) + 1.0) / 2.0
            // Carrier amplitude: 1000 at trough, 3000 at peak — always above VAD threshold
            val amplitude = 1000.0 + envelope * 2000.0
            val sample = sin(2.0 * PI * 150.0 * t) * amplitude
            sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val frames = pcm.toList()
            .chunked(frameSize)
            .mapIndexed { index, chunk ->
                val frameStartMs = startMs + index.toLong() * frameSize * 1_000L / sampleRate
                AudioFrame(chunk.toShortArray(), sampleRate, frameStartMs)
            }

        val endMs = startMs + durationMs
        return SpeechSegment(frames = frames, startMs = startMs, endMs = endMs)
    }
}
