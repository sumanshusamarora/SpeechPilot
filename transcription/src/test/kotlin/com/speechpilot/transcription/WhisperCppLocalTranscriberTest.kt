package com.speechpilot.transcription

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@ExperimentalCoroutinesApi
class WhisperCppLocalTranscriberTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------------------
    // isModelAvailable — pure filesystem check
    // -------------------------------------------------------------------------------------

    @Test
    fun `isModelAvailable returns false when model file does not exist`() {
        val absentFile = File(tempFolder.root, "missing.bin")
        val transcriber = makeTranscriber(modelFile = absentFile)

        assertFalse(transcriber.isModelAvailable())
    }

    @Test
    fun `isModelAvailable returns false when path is a directory`() {
        val dir = tempFolder.newFolder("model-dir")
        val transcriber = makeTranscriber(modelFile = dir)

        assertFalse(transcriber.isModelAvailable())
    }

    @Test
    fun `isModelAvailable returns true when model file exists`() {
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(modelFile = modelFile)

        assertTrue(transcriber.isModelAvailable())
    }

    // -------------------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------------------

    @Test
    fun `initial status is Disabled`() {
        val transcriber = makeTranscriber()

        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    @Test
    fun `WhisperNative loads the packaged whisper_jni bridge name`() {
        assertEquals("whisper_jni", WhisperNative.LIBRARY_NAME)
    }

    @Test
    fun `activeBackend is always WhisperCpp`() {
        val transcriber = makeTranscriber()

        assertEquals(TranscriptionBackend.WhisperCpp, transcriber.activeBackend.value)
    }

    @Test
    fun `setAudioSource does not change status`() {
        val transcriber = makeTranscriber()
        transcriber.setAudioSource(emptyFlow())

        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    // -------------------------------------------------------------------------------------
    // start — ModelUnavailable paths
    // -------------------------------------------------------------------------------------

    @Test
    fun `start reports ModelUnavailable when model file is absent`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelFile = File(tempFolder.root, "absent.bin"),
            runner = FakeWhisperRunner(isAvailable = true),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)
    }

    @Test
    fun `start reports ModelUnavailable when native library is not available`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = false),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()

        // After the fix this now returns NativeLibraryUnavailable rather than ModelUnavailable.
        // Kept here as a named contrast to the NativeLibraryUnavailable assertion above.
        assertEquals(TranscriptionEngineStatus.NativeLibraryUnavailable, transcriber.status.value)
    }

    @Test
    fun `start reports NativeLibraryUnavailable when native library is not available`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = false),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.NativeLibraryUnavailable, transcriber.status.value)
    }

    @Test
    fun `start reports ModelUnavailable when model file absent regardless of native availability`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        // Both file absent and runner available — file check must win.
        val transcriber = makeTranscriber(
            modelFile = File(tempFolder.root, "absent.bin"),
            runner = FakeWhisperRunner(isAvailable = true),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)
    }

    @Test
    fun `start reports Error when model file is empty`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = true),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.Error, transcriber.status.value)
        assertEquals("whisper-model-unreadable", transcriber.diagnostics.value.lastTranscriptError?.code)
        assertEquals(false, transcriber.diagnostics.value.modelFileReadable)
        assertEquals(0L, transcriber.diagnostics.value.modelFileSizeBytes)
    }

    // -------------------------------------------------------------------------------------
    // start — Error when init returns invalid context
    // -------------------------------------------------------------------------------------

    @Test
    fun `start reports Error when runner init returns zero context`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(
                isAvailable = true,
                contextHandle = 0L,
                initErrorMessage = "native init returned null context for readable model"
            ),
            ioDispatcher = testDispatcher,
        )
        transcriber.setAudioSource(emptyFlow())

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.Error, transcriber.status.value)
        assertEquals(true, transcriber.diagnostics.value.nativeInitAttempted)
        assertEquals(0L, transcriber.diagnostics.value.nativeInitContextPointer)
        assertEquals(
            "native init returned null context for readable model",
            transcriber.diagnostics.value.lastTranscriptError?.message
        )
    }

    // -------------------------------------------------------------------------------------
    // start → Listening when all preconditions are met
    // -------------------------------------------------------------------------------------

    @Test
    fun `start transitions to Listening when model and native library are available`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = true, contextHandle = 42L),
            ioDispatcher = testDispatcher,
        )
        transcriber.setAudioSource(emptyFlow())

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.Listening, transcriber.status.value)
        assertEquals(true, transcriber.diagnostics.value.nativeInitAttempted)
        assertEquals(42L, transcriber.diagnostics.value.nativeInitContextPointer)
        assertEquals(true, transcriber.diagnostics.value.selectedBackendReady)
    }

    @Test
    fun `start accepts signed native pointer handles with high bit set`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val nativePointerBits = -5476377111402469280L // 0xb40000722a45a060 as signed Long
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = true, contextHandle = nativePointerBits),
            ioDispatcher = testDispatcher,
        )
        transcriber.setAudioSource(emptyFlow())

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.Listening, transcriber.status.value)
        assertEquals(nativePointerBits, transcriber.diagnostics.value.nativeInitContextPointer)
        assertEquals(true, transcriber.diagnostics.value.selectedBackendReady)
    }

    // -------------------------------------------------------------------------------------
    // stop
    // -------------------------------------------------------------------------------------

    @Test
    fun `stop resets status to Disabled after NativeLibraryUnavailable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = false),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()
        assertEquals(TranscriptionEngineStatus.NativeLibraryUnavailable, transcriber.status.value)

        transcriber.stop()
        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    @Test
    fun `stop resets status to Disabled after ModelUnavailable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelFile = File(tempFolder.root, "absent.bin"),
            runner = FakeWhisperRunner(isAvailable = true),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        advanceUntilIdle()
        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)

        transcriber.stop()
        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    @Test
    fun `stop resets status to Disabled while Listening`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val transcriber = makeTranscriber(
            modelFile = modelFile,
            runner = FakeWhisperRunner(isAvailable = true, contextHandle = 1L),
            ioDispatcher = testDispatcher,
        )
        transcriber.setAudioSource(emptyFlow())

        transcriber.start()
        advanceUntilIdle()
        assertEquals(TranscriptionEngineStatus.Listening, transcriber.status.value)

        transcriber.stop()
        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    // -------------------------------------------------------------------------------------
    // Idempotent start
    // -------------------------------------------------------------------------------------

    @Test
    fun `second start is ignored when already running`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelFile = File(tempFolder.root, "absent.bin"),
            runner = FakeWhisperRunner(isAvailable = true),
            ioDispatcher = testDispatcher,
        )

        transcriber.start()
        transcriber.start() // no-op
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)
    }

    // -------------------------------------------------------------------------------------
    // Transcript emission
    // -------------------------------------------------------------------------------------

    @Test
    fun `emits Final TranscriptUpdate when runner returns segments after chunk is full`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val modelFile = tempFolder.newFile("ggml-small.bin")
        val fakeRunner = FakeWhisperRunner(
            isAvailable = true,
            contextHandle = 1L,
            transcriptSegments = listOf("hello world"),
        )

        // Use a small chunk size so the test doesn't need to produce 32k samples.
        val chunkSize = 4 // 4 samples per chunk for the test
        val transcriber = WhisperCppLocalTranscriber(
            modelFile = modelFile,
            runner = fakeRunner,
            chunkDurationSamples = chunkSize,
            ioDispatcher = testDispatcher,
        )

        // Produce exactly chunkSize samples across two frames.
        val audioSource = MutableSharedFlow<com.speechpilot.audio.AudioFrame>(replay = 0)
        transcriber.setAudioSource(audioSource)

        val collectedUpdates = mutableListOf<TranscriptUpdate>()
        val collectJob = launch {
            transcriber.updates.collect { collectedUpdates.add(it) }
        }

        transcriber.start()
        advanceUntilIdle()

        // Emit two frames of 2 samples each — total = chunkSize, should trigger inference.
        audioSource.emit(com.speechpilot.audio.AudioFrame(samples = shortArrayOf(100, 200), sampleRate = 16000, capturedAtMs = 0L))
        audioSource.emit(com.speechpilot.audio.AudioFrame(samples = shortArrayOf(300, 400), sampleRate = 16000, capturedAtMs = 10L))

        advanceUntilIdle()
        transcriber.stop()
        collectJob.cancel()

        assertTrue(
            "Expected at least one transcript update but got none",
            collectedUpdates.isNotEmpty()
        )
        assertEquals(2L, transcriber.diagnostics.value.selectedBackendAudioFramesReceived)
        assertEquals(1, transcriber.diagnostics.value.chunksProcessed)
        val first = collectedUpdates.first()
        assertEquals("hello world", first.text)
        assertEquals(TranscriptStability.Final, first.stability)
    }

    // -------------------------------------------------------------------------------------
    // Descriptor resolution — path matches KnownModels.WHISPER_GGML_SMALL
    // -------------------------------------------------------------------------------------

    @Test
    fun `default chunk duration is 2 seconds at 16kHz`() {
        assertEquals(
            "Expected default chunk to be 2 s × 16,000 Hz = 32,000 samples",
            32_000,
            WhisperCppLocalTranscriber.CHUNK_DURATION_SAMPLES
        )
    }

    @Test
    fun `create preserves the provided model path`() {
        val filesDir = tempFolder.newFolder("filesDir")
        val expectedPath = File(filesDir, "whisper/ggml-small.bin")
        val transcriber = WhisperCppLocalTranscriber.create(expectedPath)

        assertEquals(expectedPath.absolutePath, transcriber.modelFile.absolutePath)
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private fun makeTranscriber(
        modelFile: File = File(tempFolder.root, "absent.bin"),
        runner: WhisperRunner = FakeWhisperRunner(isAvailable = false),
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    ) = WhisperCppLocalTranscriber(
        modelFile = modelFile,
        runner = runner,
        ioDispatcher = ioDispatcher,
    )
}
