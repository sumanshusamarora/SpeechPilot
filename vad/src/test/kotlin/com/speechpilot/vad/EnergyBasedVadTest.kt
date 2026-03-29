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
}
