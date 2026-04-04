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
 * transcript quality for accented English (e.g. Indian English). It uses the ggml-small model
 * by default.
 *
 * ## Audio processing
 *
 * Whisper operates on fixed-size audio chunks rather than streaming frame-by-frame. PCM audio
 * frames from the shared pipeline are buffered internally, and inference is run on the accumulated
 * buffer when it reaches [chunkDurationSamples] (default: 5 seconds of audio). This means:
 *
 * - Transcript updates are **chunk-based and Final-only** — no partial results are emitted.
 * - There is an inherent latency of up to [chunkDurationSamples] / [SAMPLE_RATE_HZ] seconds.
 * - Any remaining buffered audio is processed when the audio source ends.
 *
 * Callers observing [updates] should not expect low-latency partial-result streaming. This is
 * intentional and is the correct behavior for a chunk-based inference backend.
 *
 * ## Model and native library
 *
 * This backend requires:
 * 1. The ggml model binary at [modelFile] (e.g. `filesDir/whisper/ggml-small.bin`).
 * 2. The `libwhisper.so` native library bundled with the APK (via `jniLibs/`).
 *
 * If either requirement is not met, [start] will report [TranscriptionEngineStatus.ModelUnavailable]
 * immediately, and [RoutingLocalTranscriber] will activate the Android SR fallback.
 *
 * See [WhisperNative] for details on how to build and bundle the native library.
 *
 * @param modelFile Path to the ggml model binary (e.g. `filesDir/whisper/ggml-small.bin`).
 * @param runner Abstracts the native Whisper calls; defaults to [WhisperNativeRunner]. Inject
 *   a [FakeWhisperRunner] in tests to exercise lifecycle without requiring the native library.
 * @param chunkDurationSamples How many 16 kHz PCM samples to accumulate before running
 *   inference. Default is 5 seconds (80,000 samples). Lower values reduce latency at the cost
 *   of transcription accuracy.
 * @param clockMs Monotonic timestamp supplier; defaults to [System.currentTimeMillis].
 * @param ioDispatcher Dispatcher for inference and I/O; defaults to [Dispatchers.IO].
 */
class WhisperCppLocalTranscriber(
    val modelFile: File,
    private val runner: WhisperRunner = WhisperNativeRunner(),
    internal val chunkDurationSamples: Int = CHUNK_DURATION_SAMPLES,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    private val _status = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val status: StateFlow<TranscriptionEngineStatus> = _status.asStateFlow()

    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(TranscriptionBackend.WhisperCpp).asStateFlow()

    @Volatile private var shouldRun = false
    @Volatile private var audioSource: Flow<AudioFrame>? = null
    private var recognitionJob: Job? = null

    override fun setAudioSource(frames: Flow<AudioFrame>) {
        audioSource = frames
    }

    override suspend fun start() {
        if (shouldRun) return
        shouldRun = true
        _status.value = TranscriptionEngineStatus.InitializingModel

        recognitionJob = scope.launch {
            if (!isModelAvailable()) {
                _status.value = TranscriptionEngineStatus.ModelUnavailable
                return@launch
            }
            if (!runner.isAvailable) {
                _status.value = TranscriptionEngineStatus.ModelUnavailable
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
    }

    /**
     * Returns `true` if the ggml model file exists on disk.
     *
     * Marked `internal` to allow direct unit testing of model-detection logic without requiring
     * the full session lifecycle.
     */
    internal fun isModelAvailable(): Boolean = modelFile.exists() && modelFile.isFile

    /**
     * Inference loop. Called only when [isModelAvailable] returns `true` and
     * [WhisperRunner.isAvailable] is `true`.
     *
     * Collects PCM frames from [audioSource], converts them to normalized float PCM, and
     * accumulates them in a buffer. When the buffer reaches [chunkDurationSamples], Whisper
     * inference is run on the accumulated audio and any recognized text is emitted as a
     * [TranscriptStability.Final] update.
     */
    private suspend fun runInference() {
        val source = audioSource ?: run {
            if (shouldRun) _status.value = TranscriptionEngineStatus.Error
            return
        }
        withContext(ioDispatcher) {
            val buffer = ArrayList<Float>(chunkDurationSamples)
            var ctx = INVALID_CTX

            try {
                ctx = runner.init(modelFile.absolutePath)
                if (ctx <= 0L) {
                    _status.value = TranscriptionEngineStatus.Error
                    return@withContext
                }

                _status.value = TranscriptionEngineStatus.Listening

                source.collect { frame ->
                    ensureActive()
                    if (!shouldRun) return@collect

                    // Convert 16-bit PCM to normalized float (range [-1.0, 1.0]).
                    for (sample in frame.samples) {
                        buffer.add(sample.toFloat() / SHORT_MAX_FLOAT)
                    }

                    if (buffer.size >= chunkDurationSamples) {
                        processChunk(ctx, buffer.toFloatArray())
                        buffer.clear()
                    }
                }

                // Process any remaining buffered audio when the stream ends.
                if (shouldRun && buffer.isNotEmpty()) {
                    processChunk(ctx, buffer.toFloatArray())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldRun) {
                    _status.value = TranscriptionEngineStatus.Error
                }
            } finally {
                if (ctx > 0L) runner.free(ctx)
            }
        }
    }

    private fun processChunk(ctx: Long, samples: FloatArray) {
        val segments = runner.transcribe(ctx, samples)
        val text = segments.joinToString(" ").trim()
        if (text.isNotEmpty()) {
            scope.launch {
                _updates.emit(
                    TranscriptUpdate(
                        text = text,
                        stability = TranscriptStability.Final,
                        receivedAtMs = clockMs(),
                    )
                )
            }
        }
    }

    companion object {
        /** Audio sample rate expected by Whisper. */
        const val SAMPLE_RATE_HZ = 16_000

        /**
         * Default number of PCM samples to accumulate before running inference.
         * 80,000 samples = 5 seconds of audio at 16 kHz.
         */
        const val CHUNK_DURATION_SAMPLES = SAMPLE_RATE_HZ * 5

        private const val SHORT_MAX_FLOAT = Short.MAX_VALUE.toFloat()
        private const val INVALID_CTX = 0L

        /** Creates a [WhisperCppLocalTranscriber] for the given model file. */
        fun create(modelFile: File): WhisperCppLocalTranscriber =
            WhisperCppLocalTranscriber(modelFile = modelFile)
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
) : WhisperRunner {

    var initCallCount = 0
        private set
    var transcribeCallCount = 0
        private set
    var freeCallCount = 0
        private set

    override fun init(modelPath: String): Long {
        initCallCount++
        return if (isAvailable) contextHandle else 0L
    }

    override fun transcribe(ctx: Long, samples: FloatArray): List<String> {
        transcribeCallCount++
        return transcriptSegments
    }

    override fun free(ctx: Long) {
        freeCallCount++
    }
}
