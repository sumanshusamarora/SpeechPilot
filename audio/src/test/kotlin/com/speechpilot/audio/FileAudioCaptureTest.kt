package com.speechpilot.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FileAudioCaptureTest {

    // ─────────────────────────── WAV header parsing ───────────────────────────

    @Test
    fun `parseWavHeader returns correct format for mono 16kHz WAV`() {
        val wav = buildTestWav(sampleRate = 16_000, numChannels = 1, numSamplesPerChannel = 16)
        val fmt = FileAudioCapture.parseWavHeader(ByteArrayInputStream(wav))
        assertEquals(1, fmt.numChannels)
        assertEquals(16_000, fmt.sampleRate)
    }

    @Test
    fun `parseWavHeader returns correct format for stereo 44100 Hz WAV`() {
        val wav = buildTestWav(sampleRate = 44_100, numChannels = 2, numSamplesPerChannel = 16)
        val fmt = FileAudioCapture.parseWavHeader(ByteArrayInputStream(wav))
        assertEquals(2, fmt.numChannels)
        assertEquals(44_100, fmt.sampleRate)
    }

    @Test
    fun `parseWavHeader leaves stream at first audio byte`() {
        // Audio data bytes are non-zero so we can detect whether the stream is positioned correctly.
        val sampleValue: Short = 1_000
        val wav = buildTestWav(
            sampleRate = 16_000, numChannels = 1, numSamplesPerChannel = 4,
            sampleValue = sampleValue
        )
        val stream = ByteArrayInputStream(wav)
        FileAudioCapture.parseWavHeader(stream)
        // First two bytes after the header are the first sample (little-endian short = 1000)
        val b0 = stream.read()
        val b1 = stream.read()
        val firstSample = (b0 or (b1 shl 8)).toShort()
        assertEquals(sampleValue, firstSample)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseWavHeader throws for non-RIFF data`() {
        val notWav = ByteArray(64) { 0x00 }
        FileAudioCapture.parseWavHeader(ByteArrayInputStream(notWav))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseWavHeader throws for RIFF file that is not WAVE`() {
        val riffNotWave = ByteArray(64) { 0x00 }.also { buf ->
            "RIFF".toByteArray(Charsets.US_ASCII).copyInto(buf, 0)
            "AVI ".toByteArray(Charsets.US_ASCII).copyInto(buf, 8) // not WAVE
        }
        FileAudioCapture.parseWavHeader(ByteArrayInputStream(riffNotWave))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseWavHeader throws for non-PCM audio format`() {
        val wav = buildTestWav(
            sampleRate = 16_000, numChannels = 1, numSamplesPerChannel = 4,
            audioFormat = 3 // IEEE float — not PCM
        )
        FileAudioCapture.parseWavHeader(ByteArrayInputStream(wav))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseWavHeader throws for 8-bit WAV`() {
        val wav = buildTestWav(
            sampleRate = 16_000, numChannels = 1, numSamplesPerChannel = 4,
            bitsPerSample = 8
        )
        FileAudioCapture.parseWavHeader(ByteArrayInputStream(wav))
    }

    // ─────────────────────────── PCM decoding ───────────────────────────

    @Test
    fun `decodePcm16 mono returns samples unchanged`() {
        val expected = shortArrayOf(100, -200, 300, -400)
        val rawBytes = shortsToLittleEndianBytes(expected)
        val result = FileAudioCapture.decodePcm16(rawBytes, numChannels = 1)
        assertShortArrayEquals(expected, result)
    }

    @Test
    fun `decodePcm16 stereo averages channels to mono`() {
        // L=200, R=400 → mono=300
        val raw = buildStereoRaw(shortArrayOf(200), shortArrayOf(400))
        val result = FileAudioCapture.decodePcm16(raw, numChannels = 2)
        assertEquals(1, result.size)
        assertEquals(300, result[0].toInt())
    }

    @Test
    fun `decodePcm16 stereo handles multiple samples`() {
        val lefts = shortArrayOf(0, 1_000, -1_000)
        val rights = shortArrayOf(0, 0, 0)
        val raw = buildStereoRaw(lefts, rights)
        val result = FileAudioCapture.decodePcm16(raw, numChannels = 2)
        assertEquals(3, result.size)
        assertEquals(0, result[0].toInt())
        assertEquals(500, result[1].toInt())
        assertEquals(-500, result[2].toInt())
    }

    @Test
    fun `decodePcm16 stereo symmetric values average to zero`() {
        val raw = buildStereoRaw(shortArrayOf(1_000), shortArrayOf(-1_000))
        val result = FileAudioCapture.decodePcm16(raw, numChannels = 2)
        assertEquals(0, result[0].toInt())
    }

    // ─────────────────────────── readFrame ───────────────────────────

    @Test
    fun `readFrame returns full buffer when data is available`() {
        val data = ByteArray(512) { it.toByte() }
        val buf = ByteArray(512)
        val n = FileAudioCapture.readFrame(ByteArrayInputStream(data), buf)
        assertEquals(512, n)
    }

    @Test
    fun `readFrame returns bytes read when stream ends early`() {
        val data = ByteArray(100) { it.toByte() }
        val buf = ByteArray(512)
        val n = FileAudioCapture.readFrame(ByteArrayInputStream(data), buf)
        assertEquals(100, n)
    }

    // ─────────────────────────── Helpers ───────────────────────────

    /**
     * Builds a minimal standard-format PCM WAV [ByteArray].
     * Audio data is filled with [sampleValue] (defaults to 0).
     */
    private fun buildTestWav(
        sampleRate: Int,
        numChannels: Int,
        numSamplesPerChannel: Int,
        audioFormat: Int = 1,
        bitsPerSample: Int = 16,
        sampleValue: Short = 0
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val dataSize = numSamplesPerChannel * numChannels * bytesPerSample
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(audioFormat.toShort())
        buf.putShort(numChannels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * numChannels * bytesPerSample)
        buf.putShort((numChannels * bytesPerSample).toShort())
        buf.putShort(bitsPerSample.toShort())

        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)

        val totalSamples = numSamplesPerChannel * numChannels
        repeat(totalSamples) { buf.putShort(sampleValue) }

        return buf.array()
    }

    private fun shortsToLittleEndianBytes(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        return buf.array()
    }

    private fun buildStereoRaw(left: ShortArray, right: ShortArray): ByteArray {
        require(left.size == right.size)
        val buf = ByteBuffer.allocate(left.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in left.indices) {
            buf.putShort(left[i])
            buf.putShort(right[i])
        }
        return buf.array()
    }

    private fun assertShortArrayEquals(expected: ShortArray, actual: ShortArray) {
        assertEquals("Array size mismatch", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("Mismatch at index $i", expected[i], actual[i])
        }
    }
}
