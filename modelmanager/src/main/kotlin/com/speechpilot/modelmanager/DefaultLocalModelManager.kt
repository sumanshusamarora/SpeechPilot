package com.speechpilot.modelmanager

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Default implementation of [LocalModelManager].
 *
 * Models are downloaded as zip archives to a temporary staging file inside [filesDir], then
 * extracted into the final [LocalModelDescriptor.installDirName] directory. If the archive's
 * root directory matches [LocalModelDescriptor.archiveRootDir], the prefix is stripped so the
 * model contents land directly in [installDirName] without an extra nesting level.
 *
 * ## Provisioning flow
 *
 * ```
 * ensureInstalled(id)
 *   → already Ready?        → return (no-op)
 *   → NotInstalled/Failed?  → Queued
 *                             → Downloading (progress updates)
 *                             → Verifying   (if sha256 present)
 *                             → Unpacking
 *                             → Ready | Failed
 * ```
 *
 * ## Known limitations (first iteration)
 *
 * - Downloads are **not resumable**. A mid-download process kill discards the partial archive;
 *   the next [ensureInstalled] call restarts from scratch.
 * - No [androidx.work.WorkManager] scheduling. Provisioning runs in [scope] and is cancelled
 *   when the scope is cancelled (typically the ViewModel lifecycle). The download will restart
 *   on the next app launch.
 * - Only one active provisioning job per model at a time; concurrent calls are serialised by a
 *   per-model [Mutex].
 *
 * @param filesDir The app's private files directory (`Context.getFilesDir()`).
 * @param scope Coroutine scope for all provisioning jobs.
 * @param ioDispatcher Dispatcher for network and file I/O; defaults to [Dispatchers.IO].
 */
class DefaultLocalModelManager(
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocalModelManager {

    private data class ModelEntry(
        val descriptor: LocalModelDescriptor,
        val stateFlow: MutableStateFlow<ModelInstallState>,
        val mutex: Mutex = Mutex(),
    )

    private val entries: Map<String, ModelEntry>

    override val knownModels: Map<String, LocalModelDescriptor>
        get() = entries.mapValues { it.value.descriptor }

    init {
        entries = KnownModels.all.associate { descriptor ->
            val initialState = if (isInstalledOnDisk(descriptor)) {
                ModelInstallState.Ready
            } else {
                ModelInstallState.NotInstalled
            }
            descriptor.id to ModelEntry(
                descriptor = descriptor,
                stateFlow = MutableStateFlow(initialState),
            )
        }
    }

    override fun stateOf(modelId: String): StateFlow<ModelInstallState> =
        entry(modelId).stateFlow.asStateFlow()

    override fun isReady(modelId: String): Boolean =
        entry(modelId).stateFlow.value == ModelInstallState.Ready

    override suspend fun ensureInstalled(modelId: String) {
        val entry = entry(modelId)
        if (entry.stateFlow.value == ModelInstallState.Ready) return
        scheduleProvisioning(entry)
    }

    override suspend fun retry(modelId: String) {
        val entry = entry(modelId)
        if (entry.stateFlow.value !is ModelInstallState.Failed) return
        scheduleProvisioning(entry)
    }

    // ---------------------------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------------------------

    private fun entry(modelId: String): ModelEntry =
        entries[modelId] ?: error("Unknown model id: '$modelId'. Register it in KnownModels.")

    private fun scheduleProvisioning(entry: ModelEntry) {
        scope.launch { provision(entry) }
    }

    /**
     * Runs the full provisioning sequence for [entry], guarded by the entry's [Mutex] to
     * prevent concurrent executions for the same model.
     */
    private suspend fun provision(entry: ModelEntry) {
        // tryLock returns false if another coroutine is already provisioning this model.
        if (!entry.mutex.tryLock()) return
        try {
            val stateFlow = entry.stateFlow
            val descriptor = entry.descriptor

            // Re-check inside the lock to handle races.
            if (stateFlow.value == ModelInstallState.Ready) return

            stateFlow.value = ModelInstallState.Queued

            val tempFile = File(filesDir, "${descriptor.id}.download.tmp")
            val installDir = File(filesDir, descriptor.installDirName)

            // 1. Download
            withContext(ioDispatcher) {
                downloadArchive(descriptor, tempFile, stateFlow)
            }
            // Exit early if download failed (stateFlow will already be Failed).
            if (stateFlow.value is ModelInstallState.Failed) return

            // 2. Optional checksum verification
            if (descriptor.sha256 != null) {
                stateFlow.value = ModelInstallState.Verifying
                var checksumOk = false
                withContext(ioDispatcher) {
                    val actual = sha256Hex(tempFile)
                    if (actual.equals(descriptor.sha256, ignoreCase = true)) {
                        checksumOk = true
                    } else {
                        tempFile.delete()
                        stateFlow.value = ModelInstallState.Failed(
                            reason = "Checksum mismatch for ${descriptor.id}. " +
                                "Expected ${descriptor.sha256} but got $actual.",
                        )
                    }
                }
                if (!checksumOk) return
            }

            // 3. Unpack
            stateFlow.value = ModelInstallState.Unpacking
            withContext(ioDispatcher) {
                extractArchive(tempFile, installDir, descriptor.archiveRootDir)
                tempFile.delete()
            }

            // 4. Verify install
            stateFlow.value = if (isInstalledOnDisk(descriptor)) {
                ModelInstallState.Ready
            } else {
                ModelInstallState.Failed(
                    reason = "Extraction completed but expected model files were not found in " +
                        installDir.absolutePath,
                )
            }
        } catch (e: Exception) {
            entry.stateFlow.value = ModelInstallState.Failed(
                reason = e.message ?: "Unexpected error during provisioning of ${entry.descriptor.id}.",
                cause = e,
            )
            // Clean up any partial download.
            File(filesDir, "${entry.descriptor.id}.download.tmp").delete()
        } finally {
            entry.mutex.unlock()
        }
    }

    /**
     * Downloads the model archive to [destFile], posting [ModelInstallState.Downloading] updates
     * with byte-level progress throughout.
     *
     * Uses [HttpURLConnection] directly to avoid adding a third-party network dependency.
     */
    internal fun downloadArchive(
        descriptor: LocalModelDescriptor,
        destFile: File,
        stateFlow: MutableStateFlow<ModelInstallState>,
    ) {
        val conn = URL(descriptor.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                stateFlow.value = ModelInstallState.Failed(
                    reason = "HTTP $responseCode while downloading ${descriptor.id}.",
                )
                return
            }

            val totalBytes = conn.contentLengthLong
            var bytesReceived = 0L

            conn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(BUFFER_BYTES)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        bytesReceived += read
                        val progress = if (totalBytes > 0) {
                            (bytesReceived * 100L / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        stateFlow.value = ModelInstallState.Downloading(
                            progressPercent = progress,
                            bytesReceived = bytesReceived,
                            totalBytes = totalBytes,
                        )
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extracts [archive] into [targetDir], stripping the [archiveRootDir] prefix from all entry
     * names so the model contents land directly in [targetDir].
     *
     * Extraction is performed into a staging directory first. On success the staging directory
     * is atomically renamed to [targetDir], ensuring the install is not visible in a partial
     * state.
     */
    internal fun extractArchive(archive: File, targetDir: File, archiveRootDir: String) {
        val stagingDir = File(targetDir.parent, "${targetDir.name}.staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val normalized = entry.name.trimEnd('/')

                // Strip the archive root dir prefix (e.g. "vosk-model-small-en-us-0.22/...").
                val relative = when {
                    normalized.startsWith("$archiveRootDir/") ->
                        normalized.removePrefix("$archiveRootDir/")
                    normalized == archiveRootDir -> {
                        // The root dir entry itself — skip, we created stagingDir already.
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    else -> normalized
                }

                if (relative.isEmpty()) {
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                val outFile = File(stagingDir, relative)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // Atomic swap: delete old install if present, then move staging into place.
        if (targetDir.exists()) targetDir.deleteRecursively()
        stagingDir.renameTo(targetDir)
    }

    /**
     * Returns `true` if the model appears to be correctly installed on disk.
     *
     * For [ModelType.STT] Vosk models this checks for the `am/final.mdl` acoustic model file
     * (or a flat `final.mdl` for single-file layouts). For [ModelType.LLM] models the check
     * is that the directory exists and is non-empty; individual model families can tighten this
     * heuristic as needed.
     */
    internal fun isInstalledOnDisk(descriptor: LocalModelDescriptor): Boolean {
        val dir = File(filesDir, descriptor.installDirName)
        if (!dir.exists() || !dir.isDirectory) return false
        return when (descriptor.type) {
            ModelType.STT ->
                File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
            ModelType.LLM ->
                dir.listFiles()?.isNotEmpty() == true
        }
    }

    /** Returns the SHA-256 hex digest of [file]. */
    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 15_000
        internal const val READ_TIMEOUT_MS = 60_000
        internal const val BUFFER_BYTES = 8 * 1024
    }
}
