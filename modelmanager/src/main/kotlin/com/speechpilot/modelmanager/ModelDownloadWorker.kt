package com.speechpilot.modelmanager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * WorkManager worker that downloads and installs a single model asset.
 *
 * ## Inputs (via [WorkerParameters.inputData])
 * - [KEY_MODEL_ID]: String — the model ID to install (must be registered in [KnownModels]).
 *
 * ## Progress (posted via [setProgress] during execution)
 * - [KEY_WORKER_STATE]: String — `"downloading"` or `"unpacking"`
 * - [KEY_PERCENT]: Int — download progress 0–100 (or `-1` if content-length unknown)
 * - [KEY_BYTES_RECEIVED]: Long — bytes downloaded so far
 * - [KEY_TOTAL_BYTES]: Long — total expected bytes (-1 if unknown)
 *
 * ## Outputs (via [Result.success] / [Result.failure])
 * - Success: no output data — caller should re-check [isInstalledOnDisk] after completion.
 * - Failure: [KEY_ERROR_MSG] String — human-readable error description.
 *
 * ## Retry behavior
 * The worker does **not** use [Result.retry]. Retry decisions are made by the caller
 * ([WorkManagerLocalModelManager.retry]) which re-enqueues with [ExistingWorkPolicy.REPLACE].
 *
 * ## Resumability
 * Downloads are **not** resumable. A cancelled or failed worker discards the partial temp file
 * and the next run starts from zero. This is acceptable for the model sizes currently in use;
 * resumable downloads can be added in a future iteration (e.g. using Range headers + an offset
 * persisted in WorkInfo output).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MSG to "Missing model ID in worker input"))

        val descriptor = KnownModels.all.find { it.id == modelId }
            ?: return Result.failure(workDataOf(KEY_ERROR_MSG to "Unknown model ID: '$modelId'"))

        val filesDir = applicationContext.filesDir
        val tempFile = File(filesDir, "$modelId.download.tmp")

        try {
            // 1. Download
            setProgress(workDataOf(KEY_WORKER_STATE to STATE_DOWNLOADING, KEY_PERCENT to 0))
            val downloadResult = downloadToFile(descriptor, tempFile)
            if (downloadResult != null) {
                tempFile.delete()
                return Result.failure(workDataOf(KEY_ERROR_MSG to downloadResult))
            }

            // 2. Optional SHA-256 verification
            if (descriptor.sha256 != null) {
                val actual = sha256Hex(tempFile)
                if (!actual.equals(descriptor.sha256, ignoreCase = true)) {
                    tempFile.delete()
                    return Result.failure(
                        workDataOf(
                            KEY_ERROR_MSG to "Checksum mismatch for $modelId: " +
                                "expected ${descriptor.sha256} got $actual"
                        )
                    )
                }
            }

            // 3. Install
            when (descriptor.archiveFormat) {
                ModelArchiveFormat.ZIP -> {
                    setProgress(workDataOf(KEY_WORKER_STATE to STATE_UNPACKING))
                    val installDir = File(filesDir, descriptor.installDirName)
                    extractArchive(tempFile, installDir, descriptor.archiveRootDir)
                    tempFile.delete()
                }
                ModelArchiveFormat.SINGLE_FILE -> {
                    installSingleFile(tempFile, descriptor, filesDir)
                }
            }

            return Result.success()
        } catch (e: Exception) {
            tempFile.delete()
            return Result.failure(
                workDataOf(KEY_ERROR_MSG to (e.message ?: "Unexpected error installing $modelId"))
            )
        }
    }

    /**
     * Downloads [descriptor.downloadUrl] to [destFile], posting [KEY_PERCENT] progress.
     *
     * @return `null` on success; a human-readable error string on failure.
     */
    private suspend fun downloadToFile(
        descriptor: LocalModelDescriptor,
        destFile: File,
    ): String? {
        val conn = URL(descriptor.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.connect()

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return "HTTP $code downloading ${descriptor.id} from ${descriptor.downloadUrl}"
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
                        val pct = if (totalBytes > 0) {
                            (bytesReceived * 100L / totalBytes).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        setProgress(
                            workDataOf(
                                KEY_WORKER_STATE to STATE_DOWNLOADING,
                                KEY_PERCENT to pct,
                                KEY_BYTES_RECEIVED to bytesReceived,
                                KEY_TOTAL_BYTES to totalBytes,
                            )
                        )
                    }
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun extractArchive(archive: File, targetDir: File, archiveRootDir: String) {
        val stagingDir = File(targetDir.parent, "${targetDir.name}.staging")
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val normalized = entry.name.trimEnd('/')
                val relative = when {
                    archiveRootDir.isNotEmpty() && normalized.startsWith("$archiveRootDir/") ->
                        normalized.removePrefix("$archiveRootDir/")
                    normalized == archiveRootDir -> {
                        zip.closeEntry(); entry = zip.nextEntry; continue
                    }
                    else -> normalized
                }
                if (relative.isEmpty()) {
                    zip.closeEntry(); entry = zip.nextEntry; continue
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

        if (targetDir.exists()) targetDir.deleteRecursively()
        stagingDir.renameTo(targetDir)
    }

    private fun installSingleFile(
        tempFile: File,
        descriptor: LocalModelDescriptor,
        filesDir: File,
    ) {
        val installDir = File(filesDir, descriptor.installDirName)
        installDir.mkdirs()
        val destFile = File(installDir, descriptor.singleFileName)
        if (destFile.exists()) destFile.delete()
        tempFile.renameTo(destFile)
    }

    private fun sha256Hex(file: File): String {
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
        /** Input data key: model ID string. */
        const val KEY_MODEL_ID = "model_id"

        /** Progress/output key: worker phase string. */
        const val KEY_WORKER_STATE = "state"

        /** Progress key: download percentage 0–100, or -1 if unknown. */
        const val KEY_PERCENT = "percent"

        /** Progress key: bytes received so far. */
        const val KEY_BYTES_RECEIVED = "bytes_received"

        /** Progress key: total expected bytes, or -1 if unknown. */
        const val KEY_TOTAL_BYTES = "total_bytes"

        /** Failure output key: human-readable error message. */
        const val KEY_ERROR_MSG = "error_msg"

        const val STATE_DOWNLOADING = "downloading"
        const val STATE_UNPACKING   = "unpacking"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS    = 120_000
        private const val BUFFER_BYTES       = 32 * 1024

        /** Unique WorkManager work name for a given model. */
        fun workName(modelId: String) = "model-download-$modelId"
    }
}
