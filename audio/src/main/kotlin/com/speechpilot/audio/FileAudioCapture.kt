package com.speechpilot.audio

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * [AudioCapture] implementation that reads PCM audio from a file [Uri].
 *
 * Supports standard 16-bit PCM WAV files (mono or stereo). Stereo files are
 * down-mixed to mono by averaging both channels. [AudioFrame.sampleRate] reflects
 * the file's native sample rate; the downstream pipeline handles duration-based
 * calculations and accommodates any sample rate.
 *
 * Does **not** require `RECORD_AUDIO` permission.
 *
 * [AudioFrame.capturedAtMs] values are synthesised from wall-clock start time plus
 * the frame's position offset in the file, so timing-dependent pipeline components
 * (e.g. pace estimation) behave correctly.
 *
 * Throws [IOException] if the URI cannot be opened.
 * Throws [IllegalArgumentException] if the file is not a supported WAV format.
 */
class FileAudioCapture(
    private val context: Context,
    private val uri: Uri,
    private val frameSize: Int = MicrophoneCapture.FRAME_SIZE
) : AudioCapture {

    override var isCapturing: Boolean = false
        private set

    override fun frames(): Flow<AudioFrame> = flow {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open audio file: $uri")

        inputStream.use { stream ->
            val fmt = parseWavHeader(stream)
            val bytesPerFrame = frameSize * fmt.numChannels * BYTES_PER_SAMPLE
            val frameDurationMs = frameSize * 1_000L / fmt.sampleRate
            val rawBuf = ByteArray(bytesPerFrame)
            val startMs = System.currentTimeMillis()
            var frameIndex = 0L
            isCapturing = true
            try {
                while (coroutineContext.isActive) {
                    val bytesRead = readFrame(stream, rawBuf)
                    if (bytesRead < bytesPerFrame) break // EOF or incomplete final frame
                    val samples = decodePcm16(rawBuf, fmt.numChannels)
                    val capturedAtMs = startMs + frameIndex * frameDurationMs
                    emit(AudioFrame(samples, fmt.sampleRate, capturedAtMs))
                    frameIndex++
                }
            } finally {
                isCapturing = false
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun start() {}
    override suspend fun stop() {}

    internal data class WavFormat(
        val numChannels: Int,
        val sampleRate: Int
    )

    companion object {
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM

        /**
         * Parses the RIFF/WAVE header from [stream], leaving it positioned at the first
         * audio data byte.
         *
         * Validates: RIFF/WAVE identity, PCM audio format (1), 16-bit samples,
         * mono or stereo channel count.
         *
         * Internal visibility allows unit tests to exercise the parser with
         * [java.io.ByteArrayInputStream] without needing an Android [Context].
         */
        internal fun parseWavHeader(stream: InputStream): WavFormat {
            // 12-byte RIFF/WAVE identity block
            val riffHeader = ByteArray(12)
            check(readExact(stream, riffHeader)) { "File too short to be a WAV" }
            require(String(riffHeader, 0, 4, Charsets.US_ASCII) == "RIFF") {
                "Not a RIFF file"
            }
            require(String(riffHeader, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "Not a WAVE file"
            }

            var numChannels = 0
            var sampleRate = 0
            var foundFmt = false
            var foundData = false

            while (!foundData) {
                val chunkHdr = ByteArray(8)
                if (!readExact(stream, chunkHdr)) break
                val chunkId = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

                when (chunkId) {
                    "fmt " -> {
                        require(chunkSize >= 16) { "fmt chunk too small ($chunkSize bytes)" }
                        // Read the 16 standard PCM fields; skip any extension bytes.
                        val fmtData = ByteArray(16)
                        check(readExact(stream, fmtData)) { "Truncated fmt chunk" }
                        if (chunkSize > 16) stream.skip((chunkSize - 16).toLong())
                        val buf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = buf.short.toInt() and 0xFFFF
                        require(audioFormat == 1) {
                            "Only PCM WAV files are supported (audioFormat=$audioFormat)"
                        }
                        numChannels = buf.short.toInt() and 0xFFFF
                        require(numChannels in 1..2) {
                            "Only mono and stereo WAV files are supported (numChannels=$numChannels)"
                        }
                        sampleRate = buf.int
                        buf.int  // skip byte rate
                        buf.short // skip block align
                        val bitsPerSample = buf.short.toInt() and 0xFFFF
                        require(bitsPerSample == 16) {
                            "Only 16-bit PCM WAV files are supported (bitsPerSample=$bitsPerSample)"
                        }
                        foundFmt = true
                    }
                    "data" -> {
                        foundData = true
                        // Stream is now positioned at the first audio byte.
                    }
                    else -> {
                        stream.skip(chunkSize.toLong())
                    }
                }
            }

            require(foundFmt) { "WAV file has no fmt chunk" }
            require(foundData) { "WAV file has no data chunk" }
            return WavFormat(numChannels, sampleRate)
        }

        /**
         * Decodes a raw PCM-16 byte buffer to a mono [ShortArray].
         *
         * Stereo input is down-mixed to mono by averaging the left and right channels.
         */
        internal fun decodePcm16(rawBytes: ByteArray, numChannels: Int): ShortArray {
            val totalSamples = rawBytes.size / (numChannels * BYTES_PER_SAMPLE)
            val out = ShortArray(totalSamples)
            val buf = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until totalSamples) {
                if (numChannels == 1) {
                    out[i] = buf.short
                } else {
                    // Stereo → mono: average L and R
                    val l = buf.short.toInt()
                    val r = buf.short.toInt()
                    out[i] = ((l + r) / 2).toShort()
                }
            }
            return out
        }

        /** Reads exactly [buf].size bytes from [stream]. Returns false if EOF is reached first. */
        private fun readExact(stream: InputStream, buf: ByteArray): Boolean {
            var offset = 0
            while (offset < buf.size) {
                val n = stream.read(buf, offset, buf.size - offset)
                if (n < 0) return false
                offset += n
            }
            return true
        }

        /**
         * Reads up to [buf].size bytes from [stream], stopping at EOF.
         * Returns the actual number of bytes read.
         */
        internal fun readFrame(stream: InputStream, buf: ByteArray): Int {
            var offset = 0
            while (offset < buf.size) {
                val n = stream.read(buf, offset, buf.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }
    }
}
