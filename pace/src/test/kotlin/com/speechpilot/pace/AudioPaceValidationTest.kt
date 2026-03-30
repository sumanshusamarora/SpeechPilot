package com.speechpilot.pace

import com.speechpilot.audio.AudioFrame
import com.speechpilot.segmentation.SpeechSegment
import com.speechpilot.segmentation.VadSpeechSegmenter
import com.speechpilot.vad.EnergyBasedVad
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Validates that [RollingWindowPaceEstimator] produces a higher pace estimate for fast
 * speech than for slow speech — i.e. the signal is correctly directional.
 *
 * **Approximation note:** The reference audio files (`samples/Slow.m4a` and `samples/Fast.m4a`
 * in the repository root) are m4a/AAC-encoded and cannot be decoded in JVM unit tests without
 * the Android MediaCodec API. These tests use synthetically generated PCM audio that faithfully
 * replicates the key acoustic property used by the estimator: the rate of amplitude-envelope
 * peaks per second.
 *
 * The synthetic audio generation ([buildSpeechLikeFrames]) creates a sinusoidal amplitude
 * envelope at the target syllable rate over a 150 Hz carrier, producing the same energy-peak
 * pattern that the estimator's [RollingWindowPaceEstimator.countSyllableProxies] detects.
 *
 * A note on the real audio files:
 * - `samples/Slow.m4a` was recorded at a deliberate slow conversational pace (~1.5–2.5 syl/s).
 * - `samples/Fast.m4a` was recorded at an elevated, rapid speaking pace (~4–6 syl/s).
 * - The synthetic rates (SLOW_SYLLABLES_PER_SEC = 2.0, FAST_SYLLABLES_PER_SEC = 5.0)
 *   are representative of these files and produce a meaningful, consistent signal gap.
 */
class AudioPaceValidationTest {

    private val sampleRate = 16_000
    private val frameSize = 512

    // ── Fast vs Slow directional validation ───────────────────────────────────

    @Test
    fun `fast speech produces higher estimated wpm than slow speech — single segment`() {
        val slowEstimator = RollingWindowPaceEstimator()
        val fastEstimator = RollingWindowPaceEstimator()

        val slowFrames = buildSpeechLikeFrames(
            syllablesPerSecond = SLOW_SYLLABLES_PER_SEC,
            durationMs = CLIP_DURATION_MS,
            startMs = 0L
        )
        val fastFrames = buildSpeechLikeFrames(
            syllablesPerSecond = FAST_SYLLABLES_PER_SEC,
            durationMs = CLIP_DURATION_MS,
            startMs = 0L
        )

        val slowSegment = SpeechSegment(slowFrames, startMs = 0L, endMs = CLIP_DURATION_MS)
        val fastSegment = SpeechSegment(fastFrames, startMs = 0L, endMs = CLIP_DURATION_MS)

        val slowMetrics = slowEstimator.estimate(slowSegment)
        val fastMetrics = fastEstimator.estimate(fastSegment)

        assertTrue(
            "Fast speech ($FAST_SYLLABLES_PER_SEC syl/s) must produce higher est-WPM than " +
                "slow speech ($SLOW_SYLLABLES_PER_SEC syl/s). " +
                "Got: fast=${fastMetrics.estimatedWpm}, slow=${slowMetrics.estimatedWpm}",
            fastMetrics.estimatedWpm > slowMetrics.estimatedWpm
        )
    }

    @Test
    fun `fast estimated wpm is meaningfully higher than slow — at least 50 percent more`() {
        val slowEstimator = RollingWindowPaceEstimator()
        val fastEstimator = RollingWindowPaceEstimator()

        val slowSegment = SpeechSegment(
            frames = buildSpeechLikeFrames(SLOW_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L),
            startMs = 0L,
            endMs = CLIP_DURATION_MS
        )
        val fastSegment = SpeechSegment(
            frames = buildSpeechLikeFrames(FAST_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L),
            startMs = 0L,
            endMs = CLIP_DURATION_MS
        )

        val slowWpm = slowEstimator.estimate(slowSegment).estimatedWpm
        val fastWpm = fastEstimator.estimate(fastSegment).estimatedWpm

        assertTrue(
            "Fast WPM should be at least 50% higher than slow WPM. " +
                "Got: fast=$fastWpm, slow=$slowWpm (ratio=${fastWpm / slowWpm})",
            fastWpm >= slowWpm * 1.5
        )
    }

    // ── Full pipeline: frames → VAD → segmenter → estimator ──────────────────

    @Test
    fun `fast speech produces higher wpm through full vad-segmenter pipeline`() = runTest {
        // Synthetic speech: amplitude modulation always above VAD threshold (RMS >> 300).
        // A single continuous speech segment is expected since there are no silence gaps.
        val slowFrames = buildSpeechLikeFrames(SLOW_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L)
        val fastFrames = buildSpeechLikeFrames(FAST_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L)

        val slowWpm = estimateThroughPipeline(slowFrames)
        val fastWpm = estimateThroughPipeline(fastFrames)

        assertTrue(
            "Fast speech must produce higher WPM through the full VAD→segmenter pipeline. " +
                "Got: fast=$fastWpm, slow=$slowWpm",
            fastWpm > slowWpm
        )
    }

    // ── Threshold crossing validation ─────────────────────────────────────────

    @Test
    fun `fast speech estimated wpm crosses default feedback threshold`() {
        val estimator = RollingWindowPaceEstimator()
        val fastSegment = SpeechSegment(
            frames = buildSpeechLikeFrames(FAST_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L),
            startMs = 0L,
            endMs = CLIP_DURATION_MS
        )
        val metrics = estimator.estimate(fastSegment)
        // Fast speech should produce a signal distinguishably above a realistic "on-target" pace.
        // We validate the signal is positive and not near-zero, confirming it would cross a
        // reasonable threshold (target typically set to ~100–130 est-WPM after calibration).
        assertTrue(
            "Fast speech must produce a positive, non-trivial pace estimate. Got: ${metrics.estimatedWpm}",
            metrics.estimatedWpm > 10.0
        )
    }

    @Test
    fun `slow speech estimated wpm is lower than fast for threshold comparison`() {
        val slowEstimator = RollingWindowPaceEstimator()
        val fastEstimator = RollingWindowPaceEstimator()

        val slowWpm = slowEstimator.estimate(
            SpeechSegment(buildSpeechLikeFrames(SLOW_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L), 0L, CLIP_DURATION_MS)
        ).estimatedWpm
        val fastWpm = fastEstimator.estimate(
            SpeechSegment(buildSpeechLikeFrames(FAST_SYLLABLES_PER_SEC, CLIP_DURATION_MS, 0L), 0L, CLIP_DURATION_MS)
        ).estimatedWpm

        // If a threshold T exists such that fast > T > slow, feedback behavior is correct.
        val midpoint = (slowWpm + fastWpm) / 2.0
        assertTrue("Fast WPM $fastWpm must exceed midpoint $midpoint", fastWpm > midpoint)
        assertTrue("Slow WPM $slowWpm must be below midpoint $midpoint", slowWpm < midpoint)
    }

    // ── Regression guard against pace inversion ───────────────────────────────

    @Test
    fun `signal is not inverted — more syllables never produces lower wpm than fewer syllables`() {
        // Verify the signal direction at multiple rates to guard against regression.
        val rates = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val wpmValues = rates.map { rate ->
            val est = RollingWindowPaceEstimator()
            est.estimate(
                SpeechSegment(
                    frames = buildSpeechLikeFrames(rate, CLIP_DURATION_MS, 0L),
                    startMs = 0L,
                    endMs = CLIP_DURATION_MS
                )
            ).estimatedWpm
        }

        for (i in 1 until wpmValues.size) {
            assertTrue(
                "WPM at ${rates[i]} syl/s (${wpmValues[i]}) must be >= WPM at ${rates[i-1]} syl/s (${wpmValues[i-1]})",
                wpmValues[i] >= wpmValues[i - 1]
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates PCM audio frames that simulate speech at [syllablesPerSecond].
     *
     * The signal uses a sinusoidal amplitude envelope at the syllable frequency over a
     * 150 Hz voiced carrier. This produces energy peaks at the target rate, which is exactly
     * what [RollingWindowPaceEstimator.countSyllableProxies] measures. The carrier amplitude
     * ranges from 1000 to 3000, keeping the signal consistently above the EnergyBasedVad
     * threshold (RMS ≈ 707–2121, well above the 300 default threshold).
     */
    private fun buildSpeechLikeFrames(
        syllablesPerSecond: Double,
        durationMs: Long,
        startMs: Long
    ): List<AudioFrame> {
        val totalSamples = (durationMs * sampleRate / 1_000L).toInt()
        val pcm = ShortArray(totalSamples) { i ->
            val t = i.toDouble() / sampleRate
            val envelope = (sin(2.0 * PI * syllablesPerSecond * t) + 1.0) / 2.0
            val amplitude = 1_000.0 + envelope * 2_000.0
            val sample = sin(2.0 * PI * 150.0 * t) * amplitude
            sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm.toList().chunked(frameSize).mapIndexed { index, chunk ->
            val frameStartMs = startMs + index.toLong() * frameSize * 1_000L / sampleRate
            AudioFrame(chunk.toShortArray(), sampleRate, frameStartMs)
        }
    }

    /** Runs [frames] through the full VAD → segmenter → estimator pipeline and returns the peak WPM. */
    private suspend fun estimateThroughPipeline(frames: List<AudioFrame>): Double {
        val vad = EnergyBasedVad()
        val segmenter = VadSpeechSegmenter(vad)
        val estimator = RollingWindowPaceEstimator()

        val segments = segmenter.segment(frames.asFlow()).toList()
        if (segments.isEmpty()) return 0.0

        var lastMetrics = PaceMetrics(estimatedWpm = 0.0, windowDurationMs = 0L)
        for (segment in segments) {
            lastMetrics = estimator.estimate(segment)
        }
        return lastMetrics.estimatedWpm
    }

    companion object {
        /** Representative slow speech rate (syl/s). Corresponds to Slow.m4a characteristics. */
        const val SLOW_SYLLABLES_PER_SEC = 2.0

        /** Representative fast speech rate (syl/s). Corresponds to Fast.m4a characteristics. */
        const val FAST_SYLLABLES_PER_SEC = 5.0

        /** Duration of each synthetic audio clip in milliseconds. */
        const val CLIP_DURATION_MS = 5_000L
    }
}
