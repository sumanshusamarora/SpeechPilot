package com.speechpilot.transcription

import com.speechpilot.audio.AudioFrame
import kotlin.math.abs

data class WhisperChunkingConfig(
    val chunkDurationMs: Long = 2_000L,
    val overlapDurationMs: Long = 0L,
) {
    init {
        require(chunkDurationMs > 0L) { "chunkDurationMs must be > 0" }
        require(overlapDurationMs >= 0L) { "overlapDurationMs must be >= 0" }
        require(overlapDurationMs < chunkDurationMs) {
            "overlapDurationMs must be smaller than chunkDurationMs"
        }
    }

    val chunkDurationSamples: Int
        get() = msToSamples(chunkDurationMs)

    val overlapSamples: Int
        get() = msToSamples(overlapDurationMs)

    val label: String
        get() = buildString {
            append(chunkDurationMs / 1_000.0)
            append("s")
            if (overlapDurationMs > 0L) {
                append(" with ")
                append(overlapDurationMs / 1_000.0)
                append("s overlap")
            } else {
                append(" no overlap")
            }
        }

    companion object {
        val LiveDefault = WhisperChunkingConfig(chunkDurationMs = 2_000L, overlapDurationMs = 0L)
        val LongerContext = WhisperChunkingConfig(chunkDurationMs = 4_000L, overlapDurationMs = 1_000L)

        private fun msToSamples(durationMs: Long): Int =
            ((WhisperCppLocalTranscriber.SAMPLE_RATE_HZ * durationMs) / 1_000L).toInt()
    }
}

data class WhisperAudioInputReport(
    val monoInput: Boolean = true,
    val inputSampleRateHz: Int? = null,
    val outputSampleRateHz: Int = WhisperCppLocalTranscriber.SAMPLE_RATE_HZ,
    val resampledToTarget: Boolean = false,
    val audioDurationMs: Long = 0L,
    val peakAbsAmplitude: Float = 0f,
    val averageAbsAmplitude: Float = 0f,
    val clippedSampleCount: Long = 0L,
)

internal data class WhisperInferenceOutcome(
    val text: String,
    val inferenceLatencyMs: Long,
    val runtimeError: String? = null,
)

internal class WhisperAudioChunkAccumulator(
    private val chunking: WhisperChunkingConfig,
) {
    private val buffer = ArrayList<Float>()

    val bufferedSampleCount: Int
        get() = buffer.size

    private var firstInputSampleRateHz: Int? = null
    private var resampledToTarget = false
    private var totalInputSamples = 0L
    private var totalAudioDurationMs = 0L
    private var clippedSampleCount = 0L
    private var peakAbsAmplitude = 0f
    private var totalAbsAmplitude = 0.0
    private var emittedChunkCount = 0

    fun appendFrame(frame: AudioFrame): List<FloatArray> {
        if (firstInputSampleRateHz == null) {
            firstInputSampleRateHz = frame.sampleRate
        }
        if (frame.sampleRate != WhisperCppLocalTranscriber.SAMPLE_RATE_HZ) {
            resampledToTarget = true
        }

        val normalized = normalizeAndMaybeResample(frame.samples, frame.sampleRate)
        for (sample in normalized) {
            buffer.add(sample)
        }
        totalAudioDurationMs += (frame.samples.size * 1_000L) / frame.sampleRate
        return drainReadyChunks(flushRemaining = false)
    }

    fun finish(): List<FloatArray> = drainReadyChunks(flushRemaining = true)

    fun buildAudioReport(): WhisperAudioInputReport = WhisperAudioInputReport(
        inputSampleRateHz = firstInputSampleRateHz,
        outputSampleRateHz = WhisperCppLocalTranscriber.SAMPLE_RATE_HZ,
        resampledToTarget = resampledToTarget,
        audioDurationMs = totalAudioDurationMs,
        peakAbsAmplitude = peakAbsAmplitude,
        averageAbsAmplitude = if (totalInputSamples > 0) {
            (totalAbsAmplitude / totalInputSamples).toFloat()
        } else {
            0f
        },
        clippedSampleCount = clippedSampleCount,
    )

    private fun normalizeAndMaybeResample(samples: ShortArray, inputSampleRateHz: Int): FloatArray {
        totalInputSamples += samples.size
        val normalized = FloatArray(samples.size)
        for (index in samples.indices) {
            val sampleInt = samples[index].toInt()
            if (sampleInt == Short.MAX_VALUE.toInt() || sampleInt == Short.MIN_VALUE.toInt()) {
                clippedSampleCount++
            }
            val absNormalized = abs(sampleInt) / Short.MAX_VALUE.toFloat()
            peakAbsAmplitude = maxOf(peakAbsAmplitude, absNormalized)
            totalAbsAmplitude += absNormalized
            normalized[index] = sampleInt / Short.MAX_VALUE.toFloat()
        }
        return if (inputSampleRateHz == WhisperCppLocalTranscriber.SAMPLE_RATE_HZ) {
            normalized
        } else {
            resample(normalized, inputSampleRateHz, WhisperCppLocalTranscriber.SAMPLE_RATE_HZ)
        }
    }

    private fun drainReadyChunks(flushRemaining: Boolean): List<FloatArray> {
        val chunks = mutableListOf<FloatArray>()
        val chunkSize = chunking.chunkDurationSamples
        val overlapSize = chunking.overlapSamples

        while (buffer.size >= chunkSize) {
            val chunk = FloatArray(chunkSize)
            for (index in 0 until chunkSize) {
                chunk[index] = buffer[index]
            }
            chunks += chunk
            emittedChunkCount++

            if (overlapSize == 0) {
                buffer.subList(0, chunkSize).clear()
            } else {
                val overlap = ArrayList<Float>(overlapSize + buffer.size)
                for (index in (chunkSize - overlapSize) until chunkSize) {
                    overlap.add(chunk[index])
                }
                buffer.subList(0, chunkSize).clear()
                if (buffer.isNotEmpty()) {
                    overlap.addAll(buffer)
                }
                buffer.clear()
                buffer.addAll(overlap)
            }
        }

        if (flushRemaining && buffer.isNotEmpty()) {
            if (emittedChunkCount == 0 || buffer.size > overlapSize) {
                val remainder = FloatArray(buffer.size)
                for (index in buffer.indices) {
                    remainder[index] = buffer[index]
                }
                chunks += remainder
            }
            buffer.clear()
        }

        return chunks
    }

    private fun resample(
        normalized: FloatArray,
        inputSampleRateHz: Int,
        outputSampleRateHz: Int,
    ): FloatArray {
        if (normalized.isEmpty()) return normalized
        val outputSize = ((normalized.size.toLong() * outputSampleRateHz) / inputSampleRateHz)
            .toInt()
            .coerceAtLeast(1)
        val output = FloatArray(outputSize)
        val step = inputSampleRateHz.toDouble() / outputSampleRateHz.toDouble()
        for (index in 0 until outputSize) {
            val sourcePosition = index * step
            val left = sourcePosition.toInt().coerceIn(0, normalized.lastIndex)
            val right = (left + 1).coerceIn(0, normalized.lastIndex)
            val fraction = (sourcePosition - left).toFloat()
            output[index] = normalized[left] + ((normalized[right] - normalized[left]) * fraction)
        }
        return output
    }
}

internal fun runWhisperInferenceChunk(
    runner: WhisperRunner,
    ctx: Long,
    samples: FloatArray,
    clockMs: () -> Long,
): WhisperInferenceOutcome {
    val startedAtMs = clockMs()
    val segments = runner.transcribe(ctx, samples)
    val finishedAtMs = clockMs()
    val error = runner.consumeLastError()
    return WhisperInferenceOutcome(
        text = segments.joinToString(" ").trim(),
        inferenceLatencyMs = finishedAtMs - startedAtMs,
        runtimeError = error,
    )
}