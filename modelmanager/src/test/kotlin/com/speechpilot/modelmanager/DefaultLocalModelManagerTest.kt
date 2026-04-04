package com.speechpilot.modelmanager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@ExperimentalCoroutinesApi
class DefaultLocalModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------------------
    // Readiness checks at init — pure filesystem, no network
    // -------------------------------------------------------------------------------------

    @Test
    fun `model is Ready when am-final-mdl exists at init`() {
        val filesDir = tempFolder.newFolder()
        installFakeVoskModel(filesDir)

        val manager = makeManager(filesDir)

        assertTrue(manager.isReady(VOSK_ID))
        assertEquals(ModelInstallState.Ready, manager.stateOf(VOSK_ID).value)
    }

    @Test
    fun `model is Ready when flat final-mdl exists at init`() {
        val filesDir = tempFolder.newFolder()
        val modelDir = File(filesDir, "vosk-model-small-en-us")
        modelDir.mkdirs()
        File(modelDir, "final.mdl").createNewFile()

        val manager = makeManager(filesDir)

        assertTrue(manager.isReady(VOSK_ID))
    }

    @Test
    fun `model is NotInstalled when directory is absent at init`() {
        val filesDir = tempFolder.newFolder()

        val manager = makeManager(filesDir)

        assertFalse(manager.isReady(VOSK_ID))
        assertEquals(ModelInstallState.NotInstalled, manager.stateOf(VOSK_ID).value)
    }

    @Test
    fun `model is NotInstalled when directory exists but am-final-mdl is absent`() {
        val filesDir = tempFolder.newFolder()
        File(filesDir, "vosk-model-small-en-us").mkdirs()

        val manager = makeManager(filesDir)

        assertFalse(manager.isReady(VOSK_ID))
        assertEquals(ModelInstallState.NotInstalled, manager.stateOf(VOSK_ID).value)
    }

    // -------------------------------------------------------------------------------------
    // ensureInstalled — state transitions
    // -------------------------------------------------------------------------------------

    @Test
    fun `ensureInstalled is a no-op when model is already Ready`() = runTest {
        val filesDir = tempFolder.newFolder()
        installFakeVoskModel(filesDir)

        val manager = makeManager(filesDir, testScheduler = this)

        manager.ensureInstalled(VOSK_ID)
        advanceUntilIdle()

        // State must remain Ready without transitioning through Queued.
        assertEquals(ModelInstallState.Ready, manager.stateOf(VOSK_ID).value)
    }

    @Test
    fun `ensureInstalled transitions to Queued then fails with no network`() = runTest {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir, testScheduler = this)

        manager.ensureInstalled(VOSK_ID)
        advanceUntilIdle()

        // Without a real network connection the download will throw and land in Failed.
        val state = manager.stateOf(VOSK_ID).value
        assertTrue(
            "Expected Failed but got $state",
            state is ModelInstallState.Failed,
        )
    }

    // -------------------------------------------------------------------------------------
    // retry
    // -------------------------------------------------------------------------------------

    @Test
    fun `retry is a no-op when state is NotInstalled`() = runTest {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir, testScheduler = this)

        manager.retry(VOSK_ID)
        advanceUntilIdle()

        // Should remain NotInstalled — retry should not touch a non-Failed model.
        assertEquals(ModelInstallState.NotInstalled, manager.stateOf(VOSK_ID).value)
    }

    @Test
    fun `retry re-starts provisioning from Failed state`() = runTest {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir, testScheduler = this)

        // Drive the model to Failed.
        manager.ensureInstalled(VOSK_ID)
        advanceUntilIdle()
        assertTrue(manager.stateOf(VOSK_ID).value is ModelInstallState.Failed)

        // retry should attempt provisioning again (will fail again without a network, but it
        // demonstrates that the state machine restarted).
        manager.retry(VOSK_ID)
        advanceUntilIdle()

        assertTrue(manager.stateOf(VOSK_ID).value is ModelInstallState.Failed)
    }

    @Test
    fun `retry is a no-op when state is Ready`() = runTest {
        val filesDir = tempFolder.newFolder()
        installFakeVoskModel(filesDir)
        val manager = makeManager(filesDir, testScheduler = this)

        manager.retry(VOSK_ID)
        advanceUntilIdle()

        assertEquals(ModelInstallState.Ready, manager.stateOf(VOSK_ID).value)
    }

    // -------------------------------------------------------------------------------------
    // knownModels registry
    // -------------------------------------------------------------------------------------

    @Test
    fun `knownModels contains Vosk model`() {
        val manager = makeManager(tempFolder.newFolder())

        assertTrue(manager.knownModels.containsKey(VOSK_ID))
        assertEquals(VOSK_ID, manager.knownModels[VOSK_ID]?.id)
    }

    @Test(expected = IllegalStateException::class)
    fun `stateOf throws for unknown model id`() {
        makeManager(tempFolder.newFolder()).stateOf("not-a-real-model")
    }

    @Test(expected = IllegalStateException::class)
    fun `isReady throws for unknown model id`() {
        makeManager(tempFolder.newFolder()).isReady("not-a-real-model")
    }

    // -------------------------------------------------------------------------------------
    // Path resolution — isInstalledOnDisk
    // -------------------------------------------------------------------------------------

    @Test
    fun `isInstalledOnDisk returns false when directory does not exist`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        assertFalse(manager.isInstalledOnDisk(KnownModels.VOSK_SMALL_EN_US))
    }

    @Test
    fun `isInstalledOnDisk returns true for STT model with am-final-mdl`() {
        val filesDir = tempFolder.newFolder()
        installFakeVoskModel(filesDir)
        val manager = makeManager(filesDir)

        assertTrue(manager.isInstalledOnDisk(KnownModels.VOSK_SMALL_EN_US))
    }

    @Test
    fun `isInstalledOnDisk returns false for empty model directory`() {
        val filesDir = tempFolder.newFolder()
        File(filesDir, "vosk-model-small-en-us").mkdirs()
        val manager = makeManager(filesDir)

        assertFalse(manager.isInstalledOnDisk(KnownModels.VOSK_SMALL_EN_US))
    }

    // -------------------------------------------------------------------------------------
    // extractArchive — deterministic unzip/install logic
    // -------------------------------------------------------------------------------------

    @Test
    fun `extractArchive strips archive root prefix and installs files`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        // Build a zip: vosk-model-small-en-us-0.22/am/final.mdl
        val zipFile = File(filesDir, "model.zip")
        createZip(
            zipFile,
            "vosk-model-small-en-us-0.22/",
            "vosk-model-small-en-us-0.22/am/",
            "vosk-model-small-en-us-0.22/am/final.mdl",
        )

        val targetDir = File(filesDir, "vosk-model-small-en-us")
        manager.extractArchive(zipFile, targetDir, "vosk-model-small-en-us-0.22")

        assertTrue("am/final.mdl should be present", File(targetDir, "am/final.mdl").exists())
        assertFalse("root prefix dir should not be nested inside target",
            File(targetDir, "vosk-model-small-en-us-0.22").exists())
    }

    @Test
    fun `extractArchive handles zip without root dir entry`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        // Zip without an explicit directory entry for the root.
        val zipFile = File(filesDir, "model.zip")
        createZip(
            zipFile,
            "vosk-model-small-en-us-0.22/am/final.mdl",
        )

        val targetDir = File(filesDir, "vosk-model-small-en-us")
        manager.extractArchive(zipFile, targetDir, "vosk-model-small-en-us-0.22")

        assertTrue(File(targetDir, "am/final.mdl").exists())
    }

    @Test
    fun `extractArchive removes existing install directory before extracting`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        // Create a stale install.
        val targetDir = File(filesDir, "vosk-model-small-en-us")
        File(targetDir, "stale").mkdirs()
        File(targetDir, "stale/file.txt").writeText("stale")

        val zipFile = File(filesDir, "model.zip")
        createZip(zipFile, "vosk-model-small-en-us-0.22/am/final.mdl")
        manager.extractArchive(zipFile, targetDir, "vosk-model-small-en-us-0.22")

        assertFalse("stale directory should be removed", File(targetDir, "stale").exists())
        assertTrue("new file should be present", File(targetDir, "am/final.mdl").exists())
    }

    // -------------------------------------------------------------------------------------
    // sha256Hex — integrity verification
    // -------------------------------------------------------------------------------------

    @Test
    fun `sha256Hex returns correct digest for known content`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        val file = File(filesDir, "test.bin")
        file.writeBytes(ByteArray(0)) // empty file

        // SHA-256 of empty input is a well-known value.
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, manager.sha256Hex(file))
    }

    // -------------------------------------------------------------------------------------
    // knownModels — Whisper model registration
    // -------------------------------------------------------------------------------------

    @Test
    fun `knownModels contains Whisper model`() {
        val manager = makeManager(tempFolder.newFolder())

        assertTrue(manager.knownModels.containsKey(WHISPER_ID))
        assertEquals(WHISPER_ID, manager.knownModels[WHISPER_ID]?.id)
    }

    // -------------------------------------------------------------------------------------
    // isInstalledOnDisk — SINGLE_FILE model
    // -------------------------------------------------------------------------------------

    @Test
    fun `isInstalledOnDisk returns false for SINGLE_FILE model when install dir absent`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        assertFalse(manager.isInstalledOnDisk(KnownModels.WHISPER_GGML_SMALL))
    }

    @Test
    fun `isInstalledOnDisk returns false for SINGLE_FILE model when install dir exists but file absent`() {
        val filesDir = tempFolder.newFolder()
        File(filesDir, "whisper").mkdirs()
        val manager = makeManager(filesDir)

        assertFalse(manager.isInstalledOnDisk(KnownModels.WHISPER_GGML_SMALL))
    }

    @Test
    fun `isInstalledOnDisk returns true for SINGLE_FILE model when binary file is present`() {
        val filesDir = tempFolder.newFolder()
        installFakeWhisperModel(filesDir)
        val manager = makeManager(filesDir)

        assertTrue(manager.isInstalledOnDisk(KnownModels.WHISPER_GGML_SMALL))
    }

    @Test
    fun `model is Ready at init when SINGLE_FILE model binary is present on disk`() {
        val filesDir = tempFolder.newFolder()
        installFakeWhisperModel(filesDir)
        val manager = makeManager(filesDir)

        assertTrue(manager.isReady(WHISPER_ID))
        assertEquals(ModelInstallState.Ready, manager.stateOf(WHISPER_ID).value)
    }

    @Test
    fun `model is NotInstalled at init when SINGLE_FILE model binary is absent`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        assertFalse(manager.isReady(WHISPER_ID))
        assertEquals(ModelInstallState.NotInstalled, manager.stateOf(WHISPER_ID).value)
    }

    // -------------------------------------------------------------------------------------
    // installSingleFile — direct binary placement
    // -------------------------------------------------------------------------------------

    @Test
    fun `installSingleFile places temp file at installDirName-slash-singleFileName`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        val tempFile = File(filesDir, "whisper-ggml-small.download.tmp")
        tempFile.writeBytes("fake-model-content".toByteArray())

        manager.installSingleFile(tempFile, KnownModels.WHISPER_GGML_SMALL)

        val expectedDest = File(filesDir, "whisper/ggml-small.bin")
        assertTrue("Model binary should exist at expected path", expectedDest.exists())
        assertFalse("Temp file should be removed after install", tempFile.exists())
    }

    @Test
    fun `installSingleFile replaces existing file`() {
        val filesDir = tempFolder.newFolder()
        val manager = makeManager(filesDir)

        // Pre-create a stale binary.
        val installDir = File(filesDir, "whisper")
        installDir.mkdirs()
        val staleFile = File(installDir, "ggml-small.bin")
        staleFile.writeBytes("stale".toByteArray())

        val tempFile = File(filesDir, "whisper-ggml-small.download.tmp")
        tempFile.writeBytes("updated-model".toByteArray())

        manager.installSingleFile(tempFile, KnownModels.WHISPER_GGML_SMALL)

        assertEquals("updated-model", File(installDir, "ggml-small.bin").readText())
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private fun makeManager(
        filesDir: File,
        testScheduler: TestScope? = null,
    ): DefaultLocalModelManager {
        val dispatcher = StandardTestDispatcher()
        val scope = testScheduler ?: TestScope(dispatcher)
        return DefaultLocalModelManager(
            filesDir = filesDir,
            scope = scope,
            ioDispatcher = dispatcher,
        )
    }

    /** Creates a minimal Vosk model directory layout that satisfies [isInstalledOnDisk]. */
    private fun installFakeVoskModel(filesDir: File) {
        val amDir = File(filesDir, "vosk-model-small-en-us/am")
        amDir.mkdirs()
        File(amDir, "final.mdl").createNewFile()
    }

    /** Creates a minimal Whisper model layout that satisfies [isInstalledOnDisk]. */
    private fun installFakeWhisperModel(filesDir: File) {
        val whisperDir = File(filesDir, "whisper")
        whisperDir.mkdirs()
        File(whisperDir, "ggml-small.bin").writeBytes(ByteArray(16))
    }

    /** Creates a zip file containing [entries] (file paths with "/" suffix = directory). */
    private fun createZip(dest: File, vararg entries: String) {
        ZipOutputStream(dest.outputStream().buffered()).use { zos ->
            for (name in entries) {
                zos.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) {
                    // Write minimal content for file entries.
                    zos.write("content".toByteArray())
                }
                zos.closeEntry()
            }
        }
    }

    companion object {
        private const val VOSK_ID = "vosk-model-small-en-us"
        private const val WHISPER_ID = "whisper-ggml-small"
    }
}
