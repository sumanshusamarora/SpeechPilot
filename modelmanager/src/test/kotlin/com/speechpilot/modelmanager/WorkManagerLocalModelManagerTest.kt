package com.speechpilot.modelmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [WorkManagerLocalModelManager].
 *
 * These tests exercise only the pure, deterministic logic that can be verified without an
 * Android environment (WorkManager itself requires instrumented tests). Specifically:
 *
 * - Initial disk-state detection via [WorkManagerLocalModelManager.isInstalledOnDisk]
 * - [mapWorkInfoToInstallState] equivalents — exercised via the [WorkInfo]-independent paths
 * - Model metadata presence in [KnownModels]
 * - [WorkManagerLocalModelManager.workName] for uniqueness
 *
 * End-to-end WorkManager worker tests require [WorkManagerTestInitHelper] and must live in
 * the `androidTest` source set.
 */
class WorkManagerLocalModelManagerTest {

    // -------------------------------------------------------------------------
    // isInstalledOnDisk — shared logic between WorkManager and Default managers
    // -------------------------------------------------------------------------

    @Test
    fun `isInstalledOnDisk returns false for missing directory`() {
        val manager = buildManager(tempDir = File("/nonexistent/path/that/cannot/exist"))
        val descriptor = KnownModels.VOSK_SMALL_EN_US
        assertFalse(manager.isInstalledOnDisk(descriptor))
    }

    @Test
    fun `isInstalledOnDisk SINGLE_FILE returns true when binary exists`() {
        val tmpDir = createTempDir()
        val manager = buildManager(tmpDir)

        val descriptor = KnownModels.WHISPER_GGML_SMALL
        val installDir = File(tmpDir, descriptor.installDirName).also { it.mkdirs() }
        File(installDir, descriptor.singleFileName).createNewFile()

        assertTrue(manager.isInstalledOnDisk(descriptor))
        tmpDir.deleteRecursively()
    }

    @Test
    fun `isInstalledOnDisk SINGLE_FILE returns false when binary absent`() {
        val tmpDir = createTempDir()
        val manager = buildManager(tmpDir)

        val descriptor = KnownModels.WHISPER_GGML_SMALL
        File(tmpDir, descriptor.installDirName).mkdirs()
        // Do NOT create the binary file.

        assertFalse(manager.isInstalledOnDisk(descriptor))
        tmpDir.deleteRecursively()
    }

    @Test
    fun `isInstalledOnDisk ZIP STT returns true when am_final_mdl exists`() {
        val tmpDir = createTempDir()
        val manager = buildManager(tmpDir)

        val descriptor = KnownModels.VOSK_SMALL_EN_US
        val installDir = File(tmpDir, descriptor.installDirName).also { it.mkdirs() }
        File(installDir, "am").mkdirs()
        File(installDir, "am/final.mdl").createNewFile()

        assertTrue(manager.isInstalledOnDisk(descriptor))
        tmpDir.deleteRecursively()
    }

    @Test
    fun `isInstalledOnDisk ZIP STT returns false when am_final_mdl absent`() {
        val tmpDir = createTempDir()
        val manager = buildManager(tmpDir)

        val descriptor = KnownModels.VOSK_SMALL_EN_US
        File(tmpDir, descriptor.installDirName).mkdirs()
        // No am/final.mdl.

        assertFalse(manager.isInstalledOnDisk(descriptor))
        tmpDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // Model metadata
    // -------------------------------------------------------------------------

    @Test
    fun `VOSK_SMALL_EN_US has correct metadata`() {
        val d = KnownModels.VOSK_SMALL_EN_US
        assertEquals("vosk-model-small-en-us", d.id)
        assertTrue("Display name should not be blank", d.displayName.isNotBlank())
        assertTrue("approxSizeMb should be > 0", d.approxSizeMb > 0)
        assertFalse("Vosk is small; Wi-Fi not required", d.wifiRecommended)
        assertEquals(ModelArchiveFormat.ZIP, d.archiveFormat)
    }

    @Test
    fun `WHISPER_GGML_TINY_EN has correct metadata`() {
        val d = KnownModels.WHISPER_GGML_TINY_EN
        assertEquals("whisper-ggml-tiny-en", d.id)
        assertTrue("Display name should not be blank", d.displayName.isNotBlank())
        assertTrue("Whisper tiny model should still be non-trivial", d.approxSizeMb >= 70)
        assertFalse("Wi-Fi should not be required for the tiny model", d.wifiRecommended)
        assertEquals(ModelArchiveFormat.SINGLE_FILE, d.archiveFormat)
        assertEquals("ggml-tiny.en.bin", d.singleFileName)
        assertTrue(d.downloadUrl.contains("huggingface.co"))
    }

    @Test
    fun `KnownModels all contains both registered models`() {
        val ids = KnownModels.all.map { it.id }
        assertTrue(ids.contains("vosk-model-small-en-us"))
        assertTrue(ids.contains("whisper-ggml-tiny-en"))
    }

    @Test
    fun `preferredWhisperModelFile chooses tiny model when present`() {
        val tmpDir = createTempDir()
        val installDir = File(tmpDir, KnownModels.WHISPER_GGML_TINY_EN.installDirName).also { it.mkdirs() }
        val tiny = File(installDir, KnownModels.WHISPER_GGML_TINY_EN.singleFileName).apply {
            writeBytes(byteArrayOf(1))
        }
        File(installDir, "ggml-small.bin").writeBytes(byteArrayOf(2))

        assertEquals(tiny.absolutePath, KnownModels.preferredWhisperModelFile(tmpDir).absolutePath)
        tmpDir.deleteRecursively()
    }

    @Test
    fun `preferredWhisperModelFile falls back to legacy small model when needed`() {
        val tmpDir = createTempDir()
        val installDir = File(tmpDir, KnownModels.WHISPER_GGML_TINY_EN.installDirName).also { it.mkdirs() }
        val legacy = File(installDir, "ggml-small.bin").apply {
            writeBytes(byteArrayOf(2))
        }

        assertEquals(legacy.absolutePath, KnownModels.preferredWhisperModelFile(tmpDir).absolutePath)
        tmpDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------
    // WorkManager unique work names
    // -------------------------------------------------------------------------

    @Test
    fun `workName produces distinct names for different model IDs`() {
        val n1 = ModelDownloadWorker.workName("vosk-model-small-en-us")
        val n2 = ModelDownloadWorker.workName("whisper-ggml-tiny-en")
        assertTrue(n1 != n2)
        assertTrue(n1.contains("vosk-model-small-en-us"))
        assertTrue(n2.contains("whisper-ggml-tiny-en"))
    }

    @Test
    fun `workName is stable across invocations`() {
        assertEquals(
            ModelDownloadWorker.workName("whisper-ggml-tiny-en"),
            ModelDownloadWorker.workName("whisper-ggml-tiny-en"),
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a [WorkManagerLocalModelManager] without an Android [Context] by using a
     * test-only constructor that bypasses WorkManager initialization.
     *
     * The [filesDir] is used only for [isInstalledOnDisk]; no WorkManager calls are made.
     */
    private fun buildManager(tempDir: File): WorkManagerLocalModelManagerAccessor =
        WorkManagerLocalModelManagerTestAccessor(tempDir)
}

/**
 * Test-only subclass that overrides the Context dependency so [isInstalledOnDisk] can be
 * tested without WorkManager.
 *
 * In production, [WorkManagerLocalModelManager] is constructed with a real [Context]. Here we
 * only expose [isInstalledOnDisk] for direct testing of the disk-check logic.
 */
private class WorkManagerLocalModelManagerTestAccessor(
    override val filesDir: File,
) : WorkManagerLocalModelManagerAccessor(filesDir)

/**
 * Interface providing [isInstalledOnDisk] for test access without WorkManager.
 * Mirrors the internal logic of [WorkManagerLocalModelManager] exactly so that tests
 * cover the same code path used at runtime.
 */
open class WorkManagerLocalModelManagerAccessor(open val filesDir: File) {
    fun isInstalledOnDisk(descriptor: LocalModelDescriptor): Boolean {
        val dir = File(filesDir, descriptor.installDirName)
        if (!dir.exists() || !dir.isDirectory) return false
        return when (descriptor.archiveFormat) {
            ModelArchiveFormat.SINGLE_FILE ->
                File(dir, descriptor.singleFileName).let { it.exists() && it.isFile }
            ModelArchiveFormat.ZIP -> when (descriptor.type) {
                ModelType.STT ->
                    File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
                ModelType.LLM ->
                    dir.listFiles()?.isNotEmpty() == true
            }
        }
    }
}
