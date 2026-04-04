package com.speechpilot.modelmanager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Production [LocalModelManager] backed by [WorkManager].
 *
 * ## Why WorkManager?
 *
 * Model downloads (especially the ~466 MB Whisper model) are long-running network operations
 * that must survive app backgrounding. WorkManager provides:
 * - Persistence across process death
 * - Network constraint enforcement (only downloads on CONNECTED networks)
 * - Deduplication via unique work names so duplicate calls are safe
 * - Observable [WorkInfo] that maps cleanly to [ModelInstallState]
 *
 * ## Architecture
 *
 * Each model's install state is exposed as a [StateFlow] backed by a [MutableStateFlow].
 * Call [startObserving] with a [CoroutineScope] (e.g. `viewModelScope`) to begin collecting
 * [WorkInfo] updates from WorkManager and translating them into [ModelInstallState] transitions.
 * Without [startObserving], state reflects only the initial disk check.
 *
 * ## Disk-first initialization
 *
 * At construction time, each registered model is checked on disk. If already installed, state
 * starts as [ModelInstallState.Ready] without requiring a WorkManager query.
 *
 * ## Thread safety
 *
 * [MutableStateFlow] updates are safe from any thread. [WorkManager] API calls are safe from
 * any thread. The caller's [CoroutineScope] controls observation lifetime.
 *
 * @param context Application context (not Activity context — must outlive the ViewModel).
 */
class WorkManagerLocalModelManager(
    private val context: Context,
) : LocalModelManager {

    private val workManager = WorkManager.getInstance(context)
    private val filesDir: File = context.filesDir

    private val _states: Map<String, MutableStateFlow<ModelInstallState>> =
        KnownModels.all.associate { descriptor ->
            descriptor.id to MutableStateFlow(
                if (isInstalledOnDisk(descriptor)) ModelInstallState.Ready
                else ModelInstallState.NotInstalled
            )
        }

    override val knownModels: Map<String, LocalModelDescriptor>
        get() = KnownModels.all.associateBy { it.id }

    // -------------------------------------------------------------------------
    // LocalModelManager interface
    // -------------------------------------------------------------------------

    override fun stateOf(modelId: String): StateFlow<ModelInstallState> =
        requireEntry(modelId).asStateFlow()

    override fun isReady(modelId: String): Boolean =
        requireEntry(modelId).value == ModelInstallState.Ready

    override suspend fun ensureInstalled(modelId: String) {
        if (isReady(modelId)) return
        val stateFlow = requireEntry(modelId)
        // Only enqueue if not already in-flight.
        if (stateFlow.value !is ModelInstallState.Queued &&
            stateFlow.value !is ModelInstallState.Downloading &&
            stateFlow.value !is ModelInstallState.Unpacking
        ) {
            scheduleDownload(modelId)
        }
    }

    override suspend fun retry(modelId: String) {
        val stateFlow = requireEntry(modelId)
        if (stateFlow.value !is ModelInstallState.Failed) return
        scheduleDownload(modelId)
    }

    // -------------------------------------------------------------------------
    // Observation
    // -------------------------------------------------------------------------

    /**
     * Starts collecting [WorkInfo] updates for all registered models and translating them to
     * [ModelInstallState] transitions.
     *
     * **Must be called from the ViewModel init** (or equivalent) with [viewModelScope] so that
     * observation is automatically cancelled when the ViewModel is cleared.
     *
     * Returns a [Job] so callers can cancel observation independently if needed.
     */
    fun startObserving(scope: CoroutineScope): Job = scope.launch {
        KnownModels.all.forEach { descriptor ->
            launch {
                workManager
                    .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.workName(descriptor.id))
                    .collect { workInfoList ->
                        val stateFlow = _states[descriptor.id] ?: return@collect
                        val info = workInfoList.firstOrNull() ?: return@collect
                        val mapped = mapWorkInfoToInstallState(info, descriptor)
                        // Never downgrade from Ready (e.g. if WorkInfo is stale SUCCEEDED).
                        if (stateFlow.value != ModelInstallState.Ready || mapped == ModelInstallState.Ready) {
                            stateFlow.value = mapped
                        }
                    }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun requireEntry(modelId: String): MutableStateFlow<ModelInstallState> =
        _states[modelId] ?: error("Unknown model id: '$modelId'. Register it in KnownModels.")

    private fun scheduleDownload(modelId: String) {
        requireEntry(modelId).value = ModelInstallState.Queued

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniqueWork(
            ModelDownloadWorker.workName(modelId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Maps a [WorkInfo] to a [ModelInstallState], reading progress [Data] from running workers.
     */
    private fun mapWorkInfoToInstallState(
        info: WorkInfo,
        descriptor: LocalModelDescriptor,
    ): ModelInstallState = when (info.state) {
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED  -> ModelInstallState.Queued

        WorkInfo.State.RUNNING  -> {
            val progress = info.progress
            when (progress.getString(ModelDownloadWorker.KEY_WORKER_STATE)) {
                ModelDownloadWorker.STATE_UNPACKING -> ModelInstallState.Unpacking
                else -> ModelInstallState.Downloading(
                    progressPercent = progress.getInt(ModelDownloadWorker.KEY_PERCENT, -1),
                    bytesReceived   = progress.getLong(ModelDownloadWorker.KEY_BYTES_RECEIVED, 0L),
                    totalBytes      = progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, -1L),
                )
            }
        }

        WorkInfo.State.SUCCEEDED -> {
            if (isInstalledOnDisk(descriptor)) {
                ModelInstallState.Ready
            } else {
                ModelInstallState.Failed(
                    reason = "WorkManager reported success but model files not found on disk " +
                        "for ${descriptor.id}. The install may have been corrupted.",
                )
            }
        }

        WorkInfo.State.FAILED    -> {
            val msg = info.outputData.getString(ModelDownloadWorker.KEY_ERROR_MSG)
                ?: "Download failed for ${descriptor.id}"
            ModelInstallState.Failed(reason = msg)
        }

        WorkInfo.State.CANCELLED -> ModelInstallState.Failed(
            reason = "Download was cancelled for ${descriptor.id}."
        )
    }

    /**
     * Returns `true` if the model asset appears correctly installed on disk.
     * Mirrors the same logic as [DefaultLocalModelManager.isInstalledOnDisk].
     */
    internal fun isInstalledOnDisk(descriptor: LocalModelDescriptor): Boolean {
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
