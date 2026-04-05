package com.speechpilot.transcription

import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@ExperimentalCoroutinesApi
class WhisperBenchmarkRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `runComparison captures model identity and timing metrics`() = runTest {
        var nowMs = 0L
        val runner = object : WhisperRunner {
            override val isAvailable: Boolean = true

            override fun init(modelPath: String): Long {
                nowMs += 5L
                return 7L
            }

            override fun transcribe(ctx: Long, samples: FloatArray): List<String> {
                nowMs += 12L
                return listOf("hello benchmark")
            }

            override fun free(ctx: Long) = Unit

            override fun consumeLastError(): String? = null
        }

        val modelFile = readableModelFile("ggml-tiny.en.bin")
        val report = WhisperBenchmarkRunner(
            runnerFactory = { runner },
            clockMs = { nowMs },
        ).runComparison(
            sourceLabel = "sample.wav",
            frameSourceFactory = {
                flowOf(
                    AudioFrame(
                        samples = ShortArray(16) { 500 },
                        sampleRate = 16_000,
                        capturedAtMs = 0L,
                    )
                )
            },
            configs = listOf(
                WhisperBenchmarkConfig(
                    modelId = "whisper-ggml-tiny-en",
                    modelDisplayName = "Whisper tiny.en (ggml)",
                    modelFile = modelFile,
                    chunking = WhisperChunkingConfig(chunkDurationMs = 1L, overlapDurationMs = 0L),
                )
            )
        )

        val result = report.results.single()
        assertEquals("whisper-ggml-tiny-en", result.modelId)
        assertEquals("Whisper tiny.en (ggml)", result.modelDisplayName)
        assertEquals(1L, result.chunkDurationMs)
        assertEquals(0L, result.overlapDurationMs)
        assertEquals(1, result.transcriptUpdateCount)
        assertEquals("hello benchmark", result.transcriptText)
        assertEquals(12.0, result.averageInferenceLatencyMs ?: 0.0, 0.001)
        assertEquals(17L, result.totalProcessingTimeMs)
        assertFalse(result.fallbackOccurred)
    }

    @Test
    fun `runComparison reports resampling when source audio is not 16k`() = runTest {
        var capturedSampleCount = 0
        val runner = object : WhisperRunner {
            override val isAvailable: Boolean = true

            override fun init(modelPath: String): Long = 1L

            override fun transcribe(ctx: Long, samples: FloatArray): List<String> {
                capturedSampleCount = samples.size
                return listOf("resampled")
            }

            override fun free(ctx: Long) = Unit

            override fun consumeLastError(): String? = null
        }

        val modelFile = readableModelFile("ggml-base.en.bin")
        val result = WhisperBenchmarkRunner(runnerFactory = { runner }).runComparison(
            sourceLabel = "resample.wav",
            frameSourceFactory = {
                flowOf(
                    AudioFrame(
                        samples = ShortArray(8) { if (it == 0) Short.MAX_VALUE else 1_000 },
                        sampleRate = 8_000,
                        capturedAtMs = 0L,
                    )
                )
            },
            configs = listOf(
                WhisperBenchmarkConfig(
                    modelId = "whisper-ggml-base-en",
                    modelDisplayName = "Whisper base.en (ggml)",
                    modelFile = modelFile,
                    chunking = WhisperChunkingConfig(chunkDurationMs = 1L, overlapDurationMs = 0L),
                )
            )
        ).results.single()

        assertTrue(result.preprocessing.resampledToTarget)
        assertEquals(8_000, result.preprocessing.inputSampleRateHz)
        assertEquals(16_000, result.preprocessing.outputSampleRateHz)
        assertTrue(capturedSampleCount >= 16)
        assertTrue(result.preprocessing.clippedSampleCount > 0)
        assertTrue(result.preprocessing.peakAbsAmplitude > 0.99f)
    }

    @Test
    fun `runComparison reports fallback when model file is missing`() = runTest {
        val missingFile = File(tempFolder.root, "missing.bin")
        val result = WhisperBenchmarkRunner(
            runnerFactory = { FakeWhisperRunner(isAvailable = true) },
        ).runComparison(
            sourceLabel = "missing.wav",
            frameSourceFactory = { emptyFlow() },
            configs = listOf(
                WhisperBenchmarkConfig(
                    modelId = "whisper-ggml-tiny-en",
                    modelDisplayName = "Whisper tiny.en (ggml)",
                    modelFile = missingFile,
                    chunking = WhisperChunkingConfig.LiveDefault,
                )
            )
        ).results.single()

        assertTrue(result.fallbackOccurred)
        assertTrue(result.runtimeError?.contains("missing") == true)
    }

    private fun readableModelFile(name: String): File = tempFolder.newFile(name).apply {
        writeBytes(byteArrayOf(0x57, 0x48, 0x49, 0x53))
    }
}