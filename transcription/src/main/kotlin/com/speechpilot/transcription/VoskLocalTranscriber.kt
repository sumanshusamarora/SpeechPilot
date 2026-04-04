package com.speechpilot.transcription

import android.content.Context
import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
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
 * Dedicated on-device STT backend using Vosk — the **preferred** transcription path.
 *
 * Vosk provides deterministic offline speech recognition with no cloud dependency, making it
 * significantly more reliable than [AndroidSpeechRecognizerTranscriber] for continuous
 * transcript-driven WPM estimation.
 *
 * ## Audio source
 *
 * This backend does **not** open its own [android.media.AudioRecord]. Instead it receives the
 * shared PCM frame stream from the session pipeline via [setAudioSource]. Call [setAudioSource]
 * before [start]. Frames are expected to be mono 16-bit PCM at [SAMPLE_RATE_HZ].
 *
 * ## Model availability
 *
 * Vosk requires a pre-trained acoustic model in [modelDirectory]. Until the model directory is
 * present, this transcriber reports [TranscriptionEngineStatus.ModelUnavailable] and emits no
 * transcript updates. The [RoutingLocalTranscriber] will then activate the
 * [AndroidSpeechRecognizerTranscriber] as a fallback.
 *
 * The default model directory for real device use is:
 * `[Context.getFilesDir]/vosk-model-small-en-us`
 *
 * ## Setting up the model
 *
 * 1. Download a model from https://alphacephei.com/vosk/models
 *    (e.g. `vosk-model-small-en-us-0.15.zip`).
 * 2. Unzip it so that the directory is accessible at
 *    `<device-files-dir>/vosk-model-small-en-us/`.
 *    Push via ADB: `adb push vosk-model-small-en-us/ /data/data/<package>/files/vosk-model-small-en-us/`
 * 3. The directory must contain `am/final.mdl` (or a top-level `final.mdl` for flat models).
 *
 * Use [VoskLocalTranscriber.create] for production code.
 */
class VoskLocalTranscriber(
    val modelDirectory: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : LocalTranscriber {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    private val _status = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val status: StateFlow<TranscriptionEngineStatus> = _status.asStateFlow()

    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(TranscriptionBackend.DedicatedLocalStt).asStateFlow()

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
            runRecognition()
        }
    }

    override suspend fun stop() {
        shouldRun = false
        recognitionJob?.cancelAndJoin()
        recognitionJob = null
        _status.value = TranscriptionEngineStatus.Disabled
    }

    /**
     * Returns true if the Vosk model directory and at least its `am/final.mdl` acoustic model
     * file are present on the device file system.
     *
     * Marked `internal` (rather than `private`) to allow direct unit-testing of model-detection
     * logic without requiring full session lifecycle setup.
     */
    internal fun isModelAvailable(): Boolean {
        if (!modelDirectory.exists() || !modelDirectory.isDirectory) return false
        // Validate that the directory is a real Vosk model by checking a known mandatory file.
        return File(modelDirectory, "am/final.mdl").exists() ||
            File(modelDirectory, "final.mdl").exists()
    }

    /**
     * Recognition loop. Called only when [isModelAvailable] returns true.
     *
     * Opens the Vosk [org.vosk.Model] and [org.vosk.Recognizer], then collects PCM frames from
     * [audioSource] and feeds them to the recognizer. Partial and final results are emitted via
     * [_updates]. Vosk resources are released in the `finally` block regardless of how the loop
     * exits (normal completion, cancellation, or error).
     */
    private suspend fun runRecognition() {
        val source = audioSource ?: run {
            if (shouldRun) _status.value = TranscriptionEngineStatus.Error
            return
        }
        withContext(ioDispatcher) {
            var model: org.vosk.Model? = null
            var recognizer: org.vosk.Recognizer? = null
            try {
                val loadedModel = org.vosk.Model(modelDirectory.absolutePath)
                model = loadedModel
                val rec = org.vosk.Recognizer(loadedModel, SAMPLE_RATE_HZ.toFloat())
                recognizer = rec
                rec.setWords(true)

                _status.value = TranscriptionEngineStatus.Listening

                source.collect { frame ->
                    if (!shouldRun) return@collect
                    val bytes = frame.samples.toLeByteArray()
                    if (rec.acceptWaveForm(bytes, bytes.size)) {
                        emitResult(rec.result, TranscriptStability.Final)
                    } else {
                        emitResult(rec.partialResult, TranscriptStability.Partial)
                    }
                }

                // Flush any buffered speech after the audio stream ends.
                if (shouldRun) {
                    emitResult(rec.finalResult, TranscriptStability.Final)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldRun) {
                    _status.value = TranscriptionEngineStatus.Error
                }
            } finally {
                recognizer?.close()
                model?.close()
            }
        }
    }

    private fun emitResult(jsonResult: String, stability: TranscriptStability) {
        val text = parseVoskResult(jsonResult, stability)
        if (text.isEmpty()) return
        scope.launch {
            _updates.emit(
                TranscriptUpdate(
                    text = text,
                    stability = stability,
                    receivedAtMs = clockMs()
                )
            )
        }
    }

    companion object {
        /** Default Vosk model directory name under [Context.getFilesDir]. */
        const val DEFAULT_MODEL_DIR = "vosk-model-small-en-us"

        /** Expected audio sample rate for the Vosk recognizer. */
        const val SAMPLE_RATE_HZ = 16_000

        /** Creates a [VoskLocalTranscriber] using the default model path for the given context. */
        fun create(context: Context): VoskLocalTranscriber =
            VoskLocalTranscriber(
                modelDirectory = File(context.applicationContext.filesDir, DEFAULT_MODEL_DIR)
            )

        /**
         * Extracts plain transcript text from a Vosk JSON result string.
         *
         * Vosk final results use `{"text": "hello world", ...}` and partial results use
         * `{"partial": "hello"}`. Returns an empty string on parse failure or missing key.
         *
         * Marked `internal` for direct unit testing without running on-device recognition.
         */
        internal fun parseVoskResult(json: String, stability: TranscriptStability): String {
            if (json.isBlank()) return ""
            return try {
                val key = if (stability == TranscriptStability.Partial) "partial" else "text"
                // Vosk emits compact JSON — a simple regex is lightweight and sufficient.
                val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
                regex.find(json)?.groupValues?.getOrElse(1) { "" }?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }
}

/** Converts 16-bit PCM samples to little-endian byte pairs as required by the Vosk recognizer. */
private fun ShortArray.toLeByteArray(): ByteArray {
    val bytes = ByteArray(size * Short.SIZE_BYTES)
    for (i in indices) {
        val s = this[i].toInt()
        bytes[i * 2] = (s and 0xFF).toByte()
        bytes[i * 2 + 1] = (s ushr 8 and 0xFF).toByte()
    }
    return bytes
}
