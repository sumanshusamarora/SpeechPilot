package com.speechpilot.transcription

import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device STT backend using Whisper.cpp.
 *
 * This backend provides an alternative to [VoskLocalTranscriber] and is intended to improve
 * transcript quality for accented English (e.g. Indian English).
 *
 * ## Audio processing
 *
 * Whisper operates on fixed-size audio chunks rather than streaming frame-by-frame. PCM audio
 * frames from the shared pipeline are buffered internally, and inference is run on the accumulated
 * buffer when it reaches [chunkConfig.chunkDurationSamples] (default: 2 seconds of audio). This means:
 *
 * - Transcript updates are **chunk-based and Final-only** — no partial results are emitted.
 * - There is an inherent latency of up to [chunkConfig.chunkDurationSamples] / [SAMPLE_RATE_HZ] seconds.
 * - Any remaining buffered audio is processed when the audio source ends.
 *
 * Callers observing [updates] should not expect low-latency partial-result streaming. This is
 * intentional and is the correct behavior for a chunk-based inference backend.
 *
 * ## Model and native library
 *
 * This backend requires:
 * 1. The configured ggml model binary at [modelFile] (e.g. `filesDir/whisper/ggml-tiny.en.bin`).
 * 2. The `libwhisper_jni.so` native library bundled with the APK (built via CMake FetchContent).
 *
 * If the model file is missing, [start] reports [TranscriptionEngineStatus.ModelUnavailable].
 * If the native library failed to load (`System.loadLibrary("whisper_jni")` threw
 * [UnsatisfiedLinkError]), [start] reports [TranscriptionEngineStatus.NativeLibraryUnavailable].
 * In both cases [RoutingLocalTranscriber] activates the Android SR fallback.
 *
 * See [WhisperNative] for details on how the native library is built and packaged.
 *
 * @param modelFile Path to the ggml model binary (e.g. `filesDir/whisper/ggml-tiny.en.bin`).
 * @param modelId Stable model identity surfaced through diagnostics and benchmark results.
 * @param modelDisplayName Human-readable model label surfaced through UI/debug output.
 * @param runner Abstracts the native Whisper calls; defaults to [WhisperNativeRunner]. Inject
 *   a [FakeWhisperRunner] in tests to exercise lifecycle without requiring the native library.
 * @param chunkConfig Explicit chunk/overlap strategy. Lower chunk durations reduce latency at the
 *   cost of transcription accuracy; overlap increases context while increasing compute cost.
 * @param clockMs Monotonic timestamp supplier; defaults to [System.currentTimeMillis].
 * @param ioDispatcher Dispatcher for inference and I/O; defaults to [Dispatchers.IO].
 */
class WhisperCppLocalTranscriber(
    val modelFile: File,
    val modelId: String = DEFAULT_MODEL_ID,
    val modelDisplayName: String = DEFAULT_MODEL_DISPLAY_NAME,
    private val runner: WhisperRunner = WhisperNativeRunner(),
    internal val chunkConfig: WhisperChunkingConfig = WhisperChunkingConfig.LiveDefault,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalTranscriber {

    private val backend = TranscriptionBackend.WhisperCpp
    private data class ModelFileSnapshot(
        val present: Boolean,
        val readable: Boolean,
        val sizeBytes: Long?,
    )

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    private val _status = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val status: StateFlow<TranscriptionEngineStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        currentModelSnapshot().let { modelSnapshot ->
            TranscriptionDiagnostics(
                selectedBackend = backend,
                activeBackend = backend,
                selectedModelId = modelId,
                selectedModelDisplayName = modelDisplayName,
                activeModelId = modelId,
                activeModelDisplayName = modelDisplayName,
                modelPath = modelFile.absolutePath,
                modelFilePresent = modelSnapshot.present,
                modelFileReadable = modelSnapshot.readable,
                modelFileSizeBytes = modelSnapshot.sizeBytes,
                nativeLibraryName = WhisperNative.LIBRARY_NAME,
                nativeLibraryLoaded = runner.isAvailable,
                nativeLibraryLoadError = WhisperNative.loadErrorMessage,
                chunkDurationMs = chunkConfig.chunkDurationMs,
                chunkOverlapMs = chunkConfig.overlapDurationMs,
            )
        }
    )
    override val diagnostics: StateFlow<TranscriptionDiagnostics> = _diagnostics.asStateFlow()

    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(backend).asStateFlow()

    @Volatile private var shouldRun = false
    @Volatile private var audioSource: Flow<AudioFrame>? = null
    private var recognitionJob: Job? = null

    override fun setAudioSource(frames: Flow<AudioFrame>) {
        audioSource = frames
        _diagnostics.value = _diagnostics.value.copy(audioSourceAttached = true)
    }

    override suspend fun start() {
        if (shouldRun) return
        shouldRun = true
        val modelSnapshot = currentModelSnapshot()
        _status.value = TranscriptionEngineStatus.InitializingModel
        _diagnostics.value = _diagnostics.value.copy(
            selectedModelId = modelId,
            selectedModelDisplayName = modelDisplayName,
            activeModelId = modelId,
            activeModelDisplayName = modelDisplayName,
            selectedBackendStatus = TranscriptionEngineStatus.InitializingModel,
            activeBackendStatus = TranscriptionEngineStatus.InitializingModel,
            fallbackBackendStatus = TranscriptionEngineStatus.Disabled,
            fallbackActive = false,
            fallbackReason = null,
            modelFilePresent = modelSnapshot.present,
            modelFileReadable = modelSnapshot.readable,
            modelFileSizeBytes = modelSnapshot.sizeBytes,
            nativeLibraryName = WhisperNative.LIBRARY_NAME,
            nativeLibraryLoaded = runner.isAvailable,
            nativeLibraryLoadError = WhisperNative.loadErrorMessage,
            nativeInitAttempted = false,
            nativeInitContextPointer = null,
            selectedBackendInitSucceeded = false,
            selectedBackendReady = false,
            selectedBackendAudioFramesReceived = 0,
            selectedBackendBufferedSamples = 0,
            chunkDurationMs = chunkConfig.chunkDurationMs,
            chunkOverlapMs = chunkConfig.overlapDurationMs,
            chunksProcessed = 0,
            selectedBackendTranscriptUpdatesEmitted = 0,
            fallbackTranscriptUpdatesEmitted = 0,
            totalTranscriptUpdatesEmitted = 0,
            audioInputSampleRateHz = null,
            audioOutputSampleRateHz = SAMPLE_RATE_HZ,
            audioResampledToTarget = false,
            audioPeakAbsAmplitude = 0f,
            audioAverageAbsAmplitude = 0f,
            audioClippedSampleCount = 0L,
            audioDurationMs = 0L,
            timeToFirstTranscriptMs = null,
            timeToFirstFinalLikeUpdateMs = null,
            averageChunkInferenceLatencyMs = null,
            totalProcessingTimeMs = null,
            lastTranscriptSource = TranscriptionBackend.None,
            lastTranscriptError = null,
            lastSuccessfulTranscriptAtMs = null,
        )

        recognitionJob = scope.launch {
            if (!modelSnapshot.present) {
                _status.value = TranscriptionEngineStatus.ModelUnavailable
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.ModelUnavailable,
                    activeBackendStatus = TranscriptionEngineStatus.ModelUnavailable,
                    modelFilePresent = false,
                    modelFileReadable = false,
                    modelFileSizeBytes = null,
                    lastTranscriptError = TranscriptionFailure(
                        code = "whisper-model-missing",
                        message = "Whisper model file missing at ${modelFile.absolutePath}",
                    ),
                )
                return@launch
            }
            if (!modelSnapshot.readable) {
                _status.value = TranscriptionEngineStatus.Error
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.Error,
                    activeBackendStatus = TranscriptionEngineStatus.Error,
                    modelFilePresent = true,
                    modelFileReadable = false,
                    modelFileSizeBytes = modelSnapshot.sizeBytes,
                    lastTranscriptError = TranscriptionFailure(
                        code = "whisper-model-unreadable",
                        message = buildString {
                            append("Whisper model file is not readable at ")
                            append(modelFile.absolutePath)
                            append(" (size=")
                            append(modelSnapshot.sizeBytes ?: 0L)
                            append(" bytes)")
                        },
                    ),
                )
                return@launch
            }
            if (!runner.isAvailable) {
                _status.value = TranscriptionEngineStatus.NativeLibraryUnavailable
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.NativeLibraryUnavailable,
                    activeBackendStatus = TranscriptionEngineStatus.NativeLibraryUnavailable,
                    lastTranscriptError = TranscriptionFailure(
                        code = "whisper-native-library-unavailable",
                        message = buildString {
                            append("Whisper native library failed to load")
                            WhisperNative.loadErrorMessage?.let {
                                append(": ")
                                append(it)
                            }
                        },
                    ),
                )
                return@launch
            }
            runInference()
        }
    }

    override suspend fun stop() {
        shouldRun = false
        recognitionJob?.cancelAndJoin()
        recognitionJob = null
        _status.value = TranscriptionEngineStatus.Disabled
        _diagnostics.value = _diagnostics.value.copy(
            selectedBackendStatus = TranscriptionEngineStatus.Disabled,
            activeBackendStatus = TranscriptionEngineStatus.Disabled,
            nativeInitContextPointer = null,
            selectedBackendReady = false,
            selectedBackendBufferedSamples = 0,
            totalProcessingTimeMs = _diagnostics.value.totalProcessingTimeMs,
        )
    }

    /**
     * Returns `true` if the ggml model file exists on disk.
     *
     * Marked `internal` to allow direct unit testing of model-detection logic without requiring
     * the full session lifecycle.
     */
    internal fun isModelAvailable(): Boolean = modelFile.exists() && modelFile.isFile

    internal fun isModelReadable(): Boolean =
        isModelAvailable() && modelFile.canRead() && modelFile.length() > 0L

    internal fun modelFileSizeBytes(): Long? = modelFile.takeIf { it.exists() && it.isFile }?.length()

    private fun currentModelSnapshot(): ModelFileSnapshot {
        val exists = modelFile.exists()
        val isFile = exists && modelFile.isFile
        val size = if (isFile) modelFile.length() else null
        val readable = isFile && modelFile.canRead() && (size ?: 0L) > 0L
        return ModelFileSnapshot(
            present = isFile,
            readable = readable,
            sizeBytes = size,
        )
    }

    /**
     * Inference loop. Called only when [isModelAvailable] returns `true` and
     * [WhisperRunner.isAvailable] is `true`.
     *
     * Collects PCM frames from [audioSource], applies the same mono/16 kHz preprocessing used by
     * benchmark runs, and accumulates them according to [chunkConfig]. Any recognized text is
     * emitted as a [TranscriptStability.Final] update.
     */
    private suspend fun runInference() {
        val source = audioSource ?: run {
            if (shouldRun) {
                _status.value = TranscriptionEngineStatus.Error
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.Error,
                    activeBackendStatus = TranscriptionEngineStatus.Error,
                    selectedBackendReady = false,
                    lastTranscriptError = TranscriptionFailure(
                        code = "whisper-audio-source-missing",
                        message = "Whisper audio source was not attached before start()",
                    ),
                )
            }
            return
        }
        withContext(ioDispatcher) {
            val accumulator = WhisperAudioChunkAccumulator(chunkConfig)
            var ctx = INVALID_CTX
            var framesReceived = 0L
            var chunksProcessed = 0
            var totalInferenceLatencyMs = 0L
            val runStartedAtMs = clockMs()

            try {
                val modelSnapshot = currentModelSnapshot()
                ctx = runner.init(modelFile.absolutePath)
                val nativeInitError = runner.consumeLastError()
                _diagnostics.value = _diagnostics.value.copy(
                    modelPath = modelFile.absolutePath,
                    modelFilePresent = modelSnapshot.present,
                    modelFileReadable = modelSnapshot.readable,
                    modelFileSizeBytes = modelSnapshot.sizeBytes,
                    nativeInitAttempted = true,
                    nativeInitContextPointer = ctx,
                )
                // JNI returns the raw native pointer bits as a signed Long. Valid 64-bit
                // addresses may appear negative when the top bit is set, so only 0 means
                // "no context".
                if (ctx == INVALID_CTX) {
                    _status.value = TranscriptionEngineStatus.Error
                    _diagnostics.value = _diagnostics.value.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Error,
                        activeBackendStatus = TranscriptionEngineStatus.Error,
                        selectedBackendReady = false,
                        lastTranscriptError = TranscriptionFailure(
                            code = "whisper-init-failed",
                            message = nativeInitError
                                ?: "Whisper native context init failed for ${modelFile.absolutePath} " +
                                    "(size=${modelSnapshot.sizeBytes ?: 0L} bytes)",
                        ),
                    )
                    return@withContext
                }

                _status.value = TranscriptionEngineStatus.Listening
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.Listening,
                    activeBackendStatus = TranscriptionEngineStatus.Listening,
                    selectedBackendInitSucceeded = true,
                    selectedBackendReady = true,
                    totalProcessingTimeMs = 0L,
                    lastTranscriptError = null,
                )

                source.collect { frame ->
                    ensureActive()
                    if (!shouldRun) return@collect
                    framesReceived++

                    val chunks = accumulator.appendFrame(frame)
                    val audioReport = accumulator.buildAudioReport()

                    if (framesReceived == 1L || framesReceived % DIAGNOSTIC_FRAME_UPDATE_INTERVAL == 0L) {
                        _diagnostics.value = _diagnostics.value.copy(
                            selectedBackendAudioFramesReceived = framesReceived,
                            selectedBackendBufferedSamples = accumulator.bufferedSampleCount,
                            audioInputSampleRateHz = audioReport.inputSampleRateHz,
                            audioOutputSampleRateHz = audioReport.outputSampleRateHz,
                            audioResampledToTarget = audioReport.resampledToTarget,
                            audioPeakAbsAmplitude = audioReport.peakAbsAmplitude,
                            audioAverageAbsAmplitude = audioReport.averageAbsAmplitude,
                            audioClippedSampleCount = audioReport.clippedSampleCount,
                            audioDurationMs = audioReport.audioDurationMs,
                        )
                    }

                    chunks.forEach { chunkSamples ->
                        chunksProcessed++
                        val outcome = processChunk(
                            ctx = ctx,
                            samples = chunkSamples,
                            chunksProcessed = chunksProcessed,
                            framesReceived = framesReceived,
                            bufferedSamples = accumulator.bufferedSampleCount,
                            runStartedAtMs = runStartedAtMs,
                        )
                        totalInferenceLatencyMs += outcome.inferenceLatencyMs
                        _diagnostics.value = _diagnostics.value.copy(
                            averageChunkInferenceLatencyMs =
                                totalInferenceLatencyMs.toDouble() / chunksProcessed,
                            totalProcessingTimeMs = clockMs() - runStartedAtMs,
                        )
                    }
                }

                // Process any remaining buffered audio when the stream ends.
                if (shouldRun) {
                    accumulator.finish().forEach { remainingSamples ->
                        chunksProcessed++
                        val outcome = processChunk(
                            ctx = ctx,
                            samples = remainingSamples,
                            chunksProcessed = chunksProcessed,
                            framesReceived = framesReceived,
                            bufferedSamples = accumulator.bufferedSampleCount,
                            runStartedAtMs = runStartedAtMs,
                        )
                        totalInferenceLatencyMs += outcome.inferenceLatencyMs
                    }
                    val audioReport = accumulator.buildAudioReport()
                    _diagnostics.value = _diagnostics.value.copy(
                        selectedBackendBufferedSamples = accumulator.bufferedSampleCount,
                        audioInputSampleRateHz = audioReport.inputSampleRateHz,
                        audioOutputSampleRateHz = audioReport.outputSampleRateHz,
                        audioResampledToTarget = audioReport.resampledToTarget,
                        audioPeakAbsAmplitude = audioReport.peakAbsAmplitude,
                        audioAverageAbsAmplitude = audioReport.averageAbsAmplitude,
                        audioClippedSampleCount = audioReport.clippedSampleCount,
                        audioDurationMs = audioReport.audioDurationMs,
                        averageChunkInferenceLatencyMs = if (chunksProcessed > 0) {
                            totalInferenceLatencyMs.toDouble() / chunksProcessed
                        } else {
                            null
                        },
                        totalProcessingTimeMs = clockMs() - runStartedAtMs,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsatisfiedLinkError) {
                if (shouldRun) {
                    _status.value = TranscriptionEngineStatus.Error
                    _diagnostics.value = _diagnostics.value.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Error,
                        activeBackendStatus = TranscriptionEngineStatus.Error,
                        selectedBackendReady = false,
                        lastTranscriptError = TranscriptionFailure(
                            code = "whisper-runtime-error",
                            message = runner.consumeLastError()
                                ?: e.message
                                ?: "Whisper transcription failed during runtime",
                        ),
                    )
                }
            } catch (e: Exception) {
                if (shouldRun) {
                    _status.value = TranscriptionEngineStatus.Error
                    _diagnostics.value = _diagnostics.value.copy(
                        selectedBackendStatus = TranscriptionEngineStatus.Error,
                        activeBackendStatus = TranscriptionEngineStatus.Error,
                        selectedBackendReady = false,
                        lastTranscriptError = TranscriptionFailure(
                            code = "whisper-runtime-error",
                            message = runner.consumeLastError()
                                ?: e.message
                                ?: "Whisper transcription failed during runtime",
                        ),
                    )
                }
            } finally {
                if (ctx != INVALID_CTX) runner.free(ctx)
            }
        }
    }

    private suspend fun processChunk(
        ctx: Long,
        samples: FloatArray,
        chunksProcessed: Int,
        framesReceived: Long,
        bufferedSamples: Int,
        runStartedAtMs: Long,
    ): WhisperInferenceOutcome {
        val outcome = runWhisperInferenceChunk(runner, ctx, samples, clockMs)
        _diagnostics.value = _diagnostics.value.copy(
            selectedBackendAudioFramesReceived = framesReceived,
            selectedBackendBufferedSamples = bufferedSamples,
            chunksProcessed = chunksProcessed,
            totalProcessingTimeMs = clockMs() - runStartedAtMs,
        )
        if (outcome.runtimeError != null && outcome.text.isBlank()) {
            _diagnostics.value = _diagnostics.value.copy(
                lastTranscriptError = TranscriptionFailure(
                    code = "whisper-runtime-error",
                    message = outcome.runtimeError,
                )
            )
        }
        if (outcome.text.isNotEmpty()) {
            val timestamp = clockMs()
            val currentDiagnostics = _diagnostics.value
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendTranscriptUpdatesEmitted =
                    currentDiagnostics.selectedBackendTranscriptUpdatesEmitted + 1,
                totalTranscriptUpdatesEmitted = currentDiagnostics.totalTranscriptUpdatesEmitted + 1,
                timeToFirstTranscriptMs = currentDiagnostics.timeToFirstTranscriptMs
                    ?: (timestamp - runStartedAtMs),
                timeToFirstFinalLikeUpdateMs = currentDiagnostics.timeToFirstFinalLikeUpdateMs
                    ?: (timestamp - runStartedAtMs),
                lastTranscriptSource = backend,
                lastSuccessfulTranscriptAtMs = timestamp,
                lastTranscriptError = null,
            )
            _updates.emit(
                TranscriptUpdate(
                    text = outcome.text,
                    stability = TranscriptStability.Final,
                    receivedAtMs = timestamp,
                )
            )
        }
        return outcome
    }

    companion object {
        /** Audio sample rate expected by Whisper. */
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_MODEL_ID = "whisper-ggml-tiny-en"
        const val DEFAULT_MODEL_DISPLAY_NAME = "Whisper tiny.en (ggml)"
        const val DEFAULT_CHUNK_DURATION_MS = 2_000L

        /**
         * Default number of PCM samples to accumulate before running inference.
         * 32,000 samples = 2 seconds of audio at 16 kHz.
         *
         * 2 seconds is a practical balance between transcript latency and recognition accuracy.
         * Lower values (< 1 s) reduce accuracy; higher values (5 s+) feel unresponsive during
         * live coaching.
         */
        const val CHUNK_DURATION_SAMPLES = SAMPLE_RATE_HZ * 2
        private const val DIAGNOSTIC_FRAME_UPDATE_INTERVAL = 8L

        private const val INVALID_CTX = 0L

        /** Creates a [WhisperCppLocalTranscriber] for the given model file. */
        fun create(
            modelFile: File,
            modelId: String = DEFAULT_MODEL_ID,
            modelDisplayName: String = DEFAULT_MODEL_DISPLAY_NAME,
            chunkConfig: WhisperChunkingConfig = WhisperChunkingConfig.LiveDefault,
        ): WhisperCppLocalTranscriber = WhisperCppLocalTranscriber(
            modelFile = modelFile,
            modelId = modelId,
            modelDisplayName = modelDisplayName,
            chunkConfig = chunkConfig,
        )
    }
}

/**
 * Abstracts native Whisper.cpp inference calls for testability.
 *
 * The default production implementation is [WhisperNativeRunner] which delegates to [WhisperNative].
 * Tests may inject [FakeWhisperRunner] to exercise lifecycle without the native library.
 */
interface WhisperRunner {
    /** `true` if the native library is loaded and inference can be performed. */
    val isAvailable: Boolean

    /**
     * Initializes a Whisper context from [modelPath].
     * @return A positive context pointer on success, or `0L`/`-1L` on failure.
     */
    fun init(modelPath: String): Long

    /**
     * Runs transcription on [samples] (normalized 16 kHz mono PCM).
     * @return List of recognized text segments; empty on failure or silence.
     */
    fun transcribe(ctx: Long, samples: FloatArray): List<String>

    /** Releases the Whisper context. Must be called when done with [ctx]. */
    fun free(ctx: Long)

    /** Returns and clears the most recent native error surfaced by the backend, if any. */
    fun consumeLastError(): String?
}

/**
 * Production [WhisperRunner] implementation that delegates to [WhisperNative] JNI bindings.
 *
 * Guards all native calls behind [WhisperNative.isAvailable] to avoid [UnsatisfiedLinkError]
 * when the native library is not bundled.
 */
class WhisperNativeRunner : WhisperRunner {

    override val isAvailable: Boolean get() = WhisperNative.isAvailable

    override fun init(modelPath: String): Long {
        if (!isAvailable) return 0L
        return WhisperNative.whisperInit(modelPath)
    }

    override fun transcribe(ctx: Long, samples: FloatArray): List<String> {
        if (!isAvailable) return emptyList()
        val result = WhisperNative.whisperFull(ctx, samples, samples.size)
        if (result != 0) return emptyList()
        val nSegments = WhisperNative.whisperFullNSegments(ctx)
        return (0 until nSegments).map { i -> WhisperNative.whisperFullGetSegmentText(ctx, i) }
    }

    override fun free(ctx: Long) {
        if (isAvailable) WhisperNative.whisperFree(ctx)
    }

    override fun consumeLastError(): String? {
        if (!isAvailable) return WhisperNative.loadErrorMessage
        return WhisperNative.whisperGetLastError()?.also {
            WhisperNative.whisperClearLastError()
        }
    }
}

/**
 * Test-only [WhisperRunner] that simulates the native library without JNI.
 *
 * Useful in unit tests to verify [WhisperCppLocalTranscriber] lifecycle and routing behavior
 * without requiring the actual native library.
 */
class FakeWhisperRunner(
    override val isAvailable: Boolean = false,
    private val contextHandle: Long = 1L,
    private val transcriptSegments: List<String> = emptyList(),
    private val initErrorMessage: String? = null,
    private val transcribeErrorMessage: String? = null,
) : WhisperRunner {

    var initCallCount = 0
        private set
    var transcribeCallCount = 0
        private set
    var freeCallCount = 0
        private set
    private var pendingError: String? = null

    override fun init(modelPath: String): Long {
        initCallCount++
        pendingError = when {
            !isAvailable -> "Whisper native library unavailable"
            contextHandle == 0L -> initErrorMessage ?: "Whisper native init returned null context"
            else -> null
        }
        return if (isAvailable) contextHandle else 0L
    }

    override fun transcribe(ctx: Long, samples: FloatArray): List<String> {
        transcribeCallCount++
        pendingError = transcribeErrorMessage
        return transcriptSegments
    }

    override fun free(ctx: Long) {
        freeCallCount++
    }

    override fun consumeLastError(): String? = pendingError.also { pendingError = null }
}
