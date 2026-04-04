package com.speechpilot.transcription

import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class VoskLocalTranscriberTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------------------
    // isModelAvailable — pure filesystem checks, no Android context required
    // -------------------------------------------------------------------------------------

    @Test
    fun `isModelAvailable returns false when directory does not exist`() {
        val absentDir = File(tempFolder.root, "no-such-model")
        val transcriber = makeTranscriber(absentDir)

        assertFalse(transcriber.isModelAvailable())
    }

    @Test
    fun `isModelAvailable returns false when directory exists but is empty`() {
        val emptyDir = tempFolder.newFolder("vosk-empty")
        val transcriber = makeTranscriber(emptyDir)

        assertFalse(transcriber.isModelAvailable())
    }

    @Test
    fun `isModelAvailable returns true when am-slash-final-mdl is present`() {
        val modelDir = tempFolder.newFolder("vosk-model-small-en-us")
        File(modelDir, "am").mkdirs()
        File(modelDir, "am/final.mdl").createNewFile()

        assertTrue(makeTranscriber(modelDir).isModelAvailable())
    }

    @Test
    fun `isModelAvailable returns true when top-level final-mdl is present`() {
        val modelDir = tempFolder.newFolder("vosk-model-flat")
        File(modelDir, "final.mdl").createNewFile()

        assertTrue(makeTranscriber(modelDir).isModelAvailable())
    }

    // -------------------------------------------------------------------------------------
    // Lifecycle and status
    // -------------------------------------------------------------------------------------

    @Test
    fun `start reports ModelUnavailable when model directory is absent`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelDir = File(tempFolder.root, "missing-model"),
            ioDispatcher = testDispatcher
        )

        transcriber.start()
        advanceUntilIdle()

        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)
    }

    @Test
    fun `initial status is Disabled`() {
        val transcriber = makeTranscriber(File(tempFolder.root, "model"))

        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    @Test
    fun `activeBackend is always DedicatedLocalStt`() {
        val transcriber = makeTranscriber(File(tempFolder.root, "model"))

        assertEquals(TranscriptionBackend.DedicatedLocalStt, transcriber.activeBackend.value)
    }

    @Test
    fun `stop resets status to Disabled after ModelUnavailable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelDir = File(tempFolder.root, "missing"),
            ioDispatcher = testDispatcher
        )

        transcriber.start()
        advanceUntilIdle()
        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)

        transcriber.stop()
        assertEquals(TranscriptionEngineStatus.Disabled, transcriber.status.value)
    }

    @Test
    fun `second start is ignored when already running`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val transcriber = makeTranscriber(
            modelDir = File(tempFolder.root, "missing"),
            ioDispatcher = testDispatcher
        )

        transcriber.start()
        transcriber.start() // should be a no-op
        advanceUntilIdle()

        // Status should be deterministic — ModelUnavailable from the first start.
        assertEquals(TranscriptionEngineStatus.ModelUnavailable, transcriber.status.value)
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private fun makeTranscriber(
        modelDir: File,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    ) = VoskLocalTranscriber(
        modelDirectory = modelDir,
        ioDispatcher = ioDispatcher
    )
}
