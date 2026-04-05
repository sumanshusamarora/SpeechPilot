package com.speechpilot.transcription

import android.content.Context
import android.net.Uri
import com.speechpilot.audio.AudioFrame
import com.speechpilot.audio.FileAudioCapture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File

data class WhisperBenchmarkConfig(
    val modelId: String,
    val modelDisplayName: String,
    val modelFile: File,
    val chunking: WhisperChunkingConfig,
    val strategyLabel: String = chunking.label,
)

data class WhisperBenchmarkResult(
    val modelId: String,
    val modelDisplayName: String,
    val chunkDurationMs: Long,
    val overlapDurationMs: Long,
    val strategyLabel: String,
    val audioDurationMs: Long,
    val transcriptText: String,
    val transcriptUpdateCount: Int,
    val timeToFirstTranscriptMs: Long?,
    val timeToFirstFinalLikeUpdateMs: Long?,
    val chunkCountProcessed: Int,
    val averageInferenceLatencyMs: Double?,
    val totalProcessingTimeMs: Long,
    val fallbackOccurred: Boolean,
    val runtimeError: String?,
    val preprocessing: WhisperAudioInputReport,
)

data class WhisperBenchmarkReport(
    val sourceLabel: String,
    val generatedAtMs: Long,
    val results: List<WhisperBenchmarkResult>,
)

class WhisperBenchmarkRunner(
    private val runnerFactory: () -> WhisperRunner = { WhisperNativeRunner() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun runComparisonForFile(
        context: Context,
        audioFileUri: Uri,
        sourceLabel: String,
        configs: List<WhisperBenchmarkConfig>,
    ): WhisperBenchmarkReport = runComparison(
        sourceLabel = sourceLabel,
        frameSourceFactory = { FileAudioCapture(context, audioFileUri).frames() },
        configs = configs,
    )

    suspend fun runComparison(
        sourceLabel: String,
        frameSourceFactory: () -> Flow<AudioFrame>,
        configs: List<WhisperBenchmarkConfig>,
    ): WhisperBenchmarkReport = withContext(ioDispatcher) {
        WhisperBenchmarkReport(
            sourceLabel = sourceLabel,
            generatedAtMs = clockMs(),
            results = configs.map { config -> runSingle(config, frameSourceFactory) },
        )
    }

    private suspend fun runSingle(
        config: WhisperBenchmarkConfig,
        frameSourceFactory: () -> Flow<AudioFrame>,
    ): WhisperBenchmarkResult {
        val runStartedAtMs = clockMs()
        val runner = runnerFactory()

        if (!config.modelFile.exists() || !config.modelFile.isFile) {
            return failedResult(
                config = config,
                startedAtMs = runStartedAtMs,
                error = "Whisper model file missing at ${config.modelFile.absolutePath}",
                fallbackOccurred = true,
            )
        }
        if (!config.modelFile.canRead() || config.modelFile.length() <= 0L) {
            return failedResult(
                config = config,
                startedAtMs = runStartedAtMs,
                error = "Whisper model file unreadable at ${config.modelFile.absolutePath}",
                fallbackOccurred = true,
            )
        }
        if (!runner.isAvailable) {
            return failedResult(
                config = config,
                startedAtMs = runStartedAtMs,
                error = runner.consumeLastError() ?: WhisperNative.loadErrorMessage ?: "Whisper native library unavailable",
                fallbackOccurred = true,
            )
        }

        val accumulator = WhisperAudioChunkAccumulator(config.chunking)
        val transcriptUpdates = mutableListOf<String>()
        var timeToFirstTranscriptMs: Long? = null
        var timeToFirstFinalLikeUpdateMs: Long? = null
        var chunkCountProcessed = 0
        var totalInferenceLatencyMs = 0L
        var runtimeError: String? = null
        var ctx = 0L

        try {
            ctx = runner.init(config.modelFile.absolutePath)
            val initError = runner.consumeLastError()
            if (ctx == 0L) {
                return failedResult(
                    config = config,
                    startedAtMs = runStartedAtMs,
                    error = initError ?: "Whisper init failed for ${config.modelFile.absolutePath}",
                    fallbackOccurred = true,
                    preprocessing = accumulator.buildAudioReport(),
                )
            }

            frameSourceFactory().collect { frame ->
                val chunks = accumulator.appendFrame(frame)
                chunks.forEach { samples ->
                    chunkCountProcessed++
                    val outcome = runWhisperInferenceChunk(runner, ctx, samples, clockMs)
                    totalInferenceLatencyMs += outcome.inferenceLatencyMs
                    if (outcome.text.isNotBlank()) {
                        transcriptUpdates += outcome.text
                        if (timeToFirstTranscriptMs == null) {
                            timeToFirstTranscriptMs = clockMs() - runStartedAtMs
                        }
                        if (timeToFirstFinalLikeUpdateMs == null) {
                            timeToFirstFinalLikeUpdateMs = clockMs() - runStartedAtMs
                        }
                    }
                    if (runtimeError == null && outcome.runtimeError != null) {
                        runtimeError = outcome.runtimeError
                    }
                }
            }

            accumulator.finish().forEach { samples ->
                chunkCountProcessed++
                val outcome = runWhisperInferenceChunk(runner, ctx, samples, clockMs)
                totalInferenceLatencyMs += outcome.inferenceLatencyMs
                if (outcome.text.isNotBlank()) {
                    transcriptUpdates += outcome.text
                    if (timeToFirstTranscriptMs == null) {
                        timeToFirstTranscriptMs = clockMs() - runStartedAtMs
                    }
                    if (timeToFirstFinalLikeUpdateMs == null) {
                        timeToFirstFinalLikeUpdateMs = clockMs() - runStartedAtMs
                    }
                }
                if (runtimeError == null && outcome.runtimeError != null) {
                    runtimeError = outcome.runtimeError
                }
            }
        } catch (error: Exception) {
            runtimeError = runtimeError ?: error.message ?: error.javaClass.simpleName
        } finally {
            if (ctx != 0L) {
                runner.free(ctx)
            }
        }

        return WhisperBenchmarkResult(
            modelId = config.modelId,
            modelDisplayName = config.modelDisplayName,
            chunkDurationMs = config.chunking.chunkDurationMs,
            overlapDurationMs = config.chunking.overlapDurationMs,
            strategyLabel = config.strategyLabel,
            audioDurationMs = accumulator.buildAudioReport().audioDurationMs,
            transcriptText = transcriptUpdates.joinToString(separator = "\n"),
            transcriptUpdateCount = transcriptUpdates.size,
            timeToFirstTranscriptMs = timeToFirstTranscriptMs,
            timeToFirstFinalLikeUpdateMs = timeToFirstFinalLikeUpdateMs,
            chunkCountProcessed = chunkCountProcessed,
            averageInferenceLatencyMs = if (chunkCountProcessed > 0) {
                totalInferenceLatencyMs.toDouble() / chunkCountProcessed
            } else {
                null
            },
            totalProcessingTimeMs = clockMs() - runStartedAtMs,
            fallbackOccurred = false,
            runtimeError = runtimeError,
            preprocessing = accumulator.buildAudioReport(),
        )
    }

    private fun failedResult(
        config: WhisperBenchmarkConfig,
        startedAtMs: Long,
        error: String,
        fallbackOccurred: Boolean,
        preprocessing: WhisperAudioInputReport = WhisperAudioInputReport(),
    ): WhisperBenchmarkResult = WhisperBenchmarkResult(
        modelId = config.modelId,
        modelDisplayName = config.modelDisplayName,
        chunkDurationMs = config.chunking.chunkDurationMs,
        overlapDurationMs = config.chunking.overlapDurationMs,
        strategyLabel = config.strategyLabel,
        audioDurationMs = preprocessing.audioDurationMs,
        transcriptText = "",
        transcriptUpdateCount = 0,
        timeToFirstTranscriptMs = null,
        timeToFirstFinalLikeUpdateMs = null,
        chunkCountProcessed = 0,
        averageInferenceLatencyMs = null,
        totalProcessingTimeMs = clockMs() - startedAtMs,
        fallbackOccurred = fallbackOccurred,
        runtimeError = error,
        preprocessing = preprocessing,
    )
}