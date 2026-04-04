package com.speechpilot.modelmanager

import kotlinx.coroutines.flow.StateFlow

/**
 * Generic interface for managing on-device AI model lifecycle.
 *
 * The manager holds a registry of [LocalModelDescriptor]s and handles download, extraction, and
 * readiness checking for each registered model. It is deliberately model-family agnostic — the
 * same interface covers Vosk STT models, future Gemma LLM models, or any other local asset that
 * needs managed provisioning.
 *
 * All provisioning state is exposed as [StateFlow] so that the UI can observe changes reactively
 * without polling.
 *
 * ## Usage pattern
 *
 * ```kotlin
 * val manager: LocalModelManager = DefaultLocalModelManager(filesDir, viewModelScope)
 *
 * // Trigger provisioning if not yet installed (no-op when already Ready).
 * manager.ensureInstalled(KnownModels.VOSK_SMALL_EN_US.id)
 *
 * // Observe state in a Composable or ViewModel.
 * manager.stateOf(KnownModels.VOSK_SMALL_EN_US.id).collect { state -> … }
 * ```
 */
interface LocalModelManager {

    /** Descriptors for all models registered with this manager, keyed by model id. */
    val knownModels: Map<String, LocalModelDescriptor>

    /**
     * Returns a [StateFlow] tracking the [ModelInstallState] for [modelId].
     *
     * @throws IllegalStateException if [modelId] is not registered.
     */
    fun stateOf(modelId: String): StateFlow<ModelInstallState>

    /**
     * Returns `true` if the model is currently [ModelInstallState.Ready].
     *
     * @throws IllegalStateException if [modelId] is not registered.
     */
    fun isReady(modelId: String): Boolean

    /**
     * Ensures [modelId] is installed. A no-op when the model is already [ModelInstallState.Ready].
     * Otherwise schedules managed provisioning (download + extraction) and updates the state flow.
     *
     * This function returns immediately; provisioning runs in the background. Observe [stateOf]
     * for progress and the final [ModelInstallState.Ready] or [ModelInstallState.Failed] outcome.
     *
     * Concurrent calls for the same model are deduplicated — only one active provisioning job
     * runs per model at a time.
     *
     * @throws IllegalStateException if [modelId] is not registered.
     */
    suspend fun ensureInstalled(modelId: String)

    /**
     * Retries provisioning for a model that is in the [ModelInstallState.Failed] state. Resets
     * the state to [ModelInstallState.Queued] and restarts the provisioning flow.
     *
     * No-op if the model is not currently [ModelInstallState.Failed].
     *
     * @throws IllegalStateException if [modelId] is not registered.
     */
    suspend fun retry(modelId: String)
}
