package com.speechpilot.vad

import com.speechpilot.audio.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyBasedVadTest {

    private val vad = EnergyBasedVad(threshold = 300.0)

    @Test
    fun `silent frame returns Silence`() {
        val frame = AudioFrame(ShortArray(512) { 0 }, 16_000, 0L)
        assertEquals(VadResult.Silence, vad.detect(frame))
    }

    @Test
    fun `loud frame returns Speech`() {
        val frame = AudioFrame(ShortArray(512) { 1_000 }, 16_000, 0L)
        assertEquals(VadResult.Speech, vad.detect(frame))
    }

    @Test
    fun `frame at exact threshold returns Speech`() {
        // Build samples whose RMS equals exactly the threshold.
        // RMS = sqrt(mean(s^2)) = threshold  =>  s = threshold (uniform samples).
        val sampleValue = 300.toShort()
        val frame = AudioFrame(ShortArray(512) { sampleValue }, 16_000, 0L)
        assertEquals(VadResult.Speech, vad.detect(frame))
    }

    @Test
    fun `empty samples returns Silence`() {
        val frame = AudioFrame(ShortArray(0), 16_000, 0L)
        assertEquals(VadResult.Silence, vad.detect(frame))
    }

    @Test
    fun `custom threshold is respected`() {
        val highThresholdVad = EnergyBasedVad(threshold = 2_000.0)
        // A frame loud enough to pass the default threshold but below the custom one.
        val frame = AudioFrame(ShortArray(512) { 1_000 }, 16_000, 0L)
        assertEquals(VadResult.Silence, highThresholdVad.detect(frame))
    }

    @Test
    fun `default threshold constant matches expected value`() {
        assertEquals(300.0, EnergyBasedVad.DEFAULT_THRESHOLD, 0.0)
    }

    @Test
    fun `negative samples are treated correctly by rms`() {
        // RMS is computed as sqrt(mean(s*s)), so negatives should produce the same
        // energy as the corresponding positive values.
        val positiveFrame = AudioFrame(ShortArray(512) { 1_000 }, 16_000, 0L)
        val negativeFrame = AudioFrame(ShortArray(512) { -1_000 }, 16_000, 0L)
        assertEquals(vad.detect(positiveFrame), vad.detect(negativeFrame))
    }
}
