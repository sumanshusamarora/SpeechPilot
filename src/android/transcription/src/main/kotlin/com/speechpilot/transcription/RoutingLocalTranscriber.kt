package com.speechpilot.transcription
import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routes between the preferred dedicated STT backend and the Android SpeechRecognizer fallback.
 *
 * Selection strategy (evaluated on [start]):
 * 1. Try the [primaryTranscriber] (Vosk-based or Whisper.cpp on-device STT).
 * 2. If it reports [TranscriptionEngineStatus.ModelUnavailable] (model file missing) or
 *    [TranscriptionEngineStatus.NativeLibraryUnavailable] (native library not loaded),
 *    fall back to [fallbackTranscriber] (Android SpeechRecognizer).
 * 3. Expose [activeBackend] so the rest of the app can observe which path is running.
 *
 * Both backends share the same [LocalTranscriber] contract. The selected backend's [updates] and
 * [status] are forwarded through this router's own flows so callers observe a single surface.
 */
class RoutingLocalTranscriber(
    private val primaryTranscriber: LocalTranscriber,
    private val fallbackTranscriber: LocalTranscriber,
    private val fallbackDelayMs: Long = FALLBACK_CHECK_DELAY_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : LocalTranscriber {
    private val selectionMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    private val _status = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val status: StateFlow<TranscriptionEngineStatus> = _status.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        TranscriptionDiagnostics(
            selectedBackend = primaryTranscriber.activeBackend.value,
            activeBackend = TranscriptionBackend.None,
            selectedModelId = primaryTranscriber.diagnostics.value.selectedModelId,
            selectedModelDisplayName = primaryTranscriber.diagnostics.value.selectedModelDisplayName,
            selectedBackendStatus = TranscriptionEngineStatus.Disabled,
            activeBackendStatus = TranscriptionEngineStatus.Disabled,
            fallbackBackendStatus = fallbackTranscriber.status.value,
        )
    )
    override val diagnostics: StateFlow<TranscriptionDiagnostics> = _diagnostics.asStateFlow()

    private val _activeBackend = MutableStateFlow(TranscriptionBackend.None)
    override val activeBackend: StateFlow<TranscriptionBackend> = _activeBackend.asStateFlow()

    private var routingJob: Job? = null
    private var activeTranscriber: LocalTranscriber? = null
    private var primaryDiagnostics = primaryTranscriber.diagnostics.value
    private var fallbackDiagnostics = fallbackTranscriber.diagnostics.value
    private var fallbackReason: TranscriptionFailure? = null
    private var primaryReadyObserved = false
    private var lastTranscriptSource = TranscriptionBackend.None
    private var lastSuccessfulTranscriptAtMs: Long? = null

    override fun setAudioSource(frames: Flow<AudioFrame>) {
        primaryTranscriber.setAudioSource(frames)
        fallbackTranscriber.setAudioSource(frames)
    }

    override suspend fun start() {
        if (routingJob?.isActive == true) return
        _status.value = TranscriptionEngineStatus.InitializingModel
        _activeBackend.value = TranscriptionBackend.None
        primaryDiagnostics = primaryTranscriber.diagnostics.value
        fallbackDiagnostics = fallbackTranscriber.diagnostics.value
        fallbackReason = null
        primaryReadyObserved = false
        lastTranscriptSource = TranscriptionBackend.None
        lastSuccessfulTranscriptAtMs = null
        recomputeDiagnostics()

        routingJob = scope.launch {
            launch {
                primaryTranscriber.diagnostics.collect { snapshot ->
                    primaryDiagnostics = snapshot
                    recomputeDiagnostics()
                }
            }
            launch {
                fallbackTranscriber.diagnostics.collect { snapshot ->
                    fallbackDiagnostics = snapshot
                    recomputeDiagnostics()
                }
            }
            launch {
                primaryTranscriber.status.collect { engineStatus ->
                    handlePrimaryStatus(engineStatus)
                }
            }
            launch {
                fallbackTranscriber.status.collect { engineStatus ->
                    if (activeTranscriber === fallbackTranscriber) {
                        _status.value = engineStatus
                    }
                    recomputeDiagnostics()
                }
            }
            launch {
                primaryTranscriber.updates.collect { update ->
                    lastTranscriptSource = primaryTranscriber.activeBackend.value
                    lastSuccessfulTranscriptAtMs = update.receivedAtMs
                    recomputeDiagnostics()
                    if (activeTranscriber === primaryTranscriber) {
                        _updates.emit(update)
                    }
                }
            }
            launch {
                fallbackTranscriber.updates.collect { update ->
                    lastTranscriptSource = fallbackTranscriber.activeBackend.value
                    lastSuccessfulTranscriptAtMs = update.receivedAtMs
                    recomputeDiagnostics()
                    if (activeTranscriber === fallbackTranscriber) {
                        _updates.emit(update)
                    }
                }
            }

            try {
                primaryTranscriber.start()
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnsatisfiedLinkError) {
                activateFallback(
                    TranscriptionFailure(
                        code = "primary-start-failed",
                        message = error.message ?: "Selected transcription backend failed to start",
                    )
                )
                return@launch
            } catch (error: Exception) {
                activateFallback(
                    TranscriptionFailure(
                        code = "primary-start-failed",
                        message = error.message ?: "Selected transcription backend failed to start",
                    )
                )
                return@launch
            }

            launch {
                kotlinx.coroutines.delay(fallbackDelayMs)
                selectPrimaryIfUnselected()
            }
        }
    }

    override suspend fun stop() {
        routingJob?.cancelAndJoin()
        routingJob = null
        activeTranscriber = null
        fallbackReason = null
        primaryReadyObserved = false
        primaryTranscriber.stop()
        if (fallbackTranscriber !== primaryTranscriber) {
            fallbackTranscriber.stop()
        }
        _status.value = TranscriptionEngineStatus.Disabled
        _activeBackend.value = TranscriptionBackend.None
        recomputeDiagnostics()
    }

    private suspend fun handlePrimaryStatus(engineStatus: TranscriptionEngineStatus) {
        if (engineStatus == TranscriptionEngineStatus.Listening ||
            engineStatus == TranscriptionEngineStatus.Restarting
        ) {
            primaryReadyObserved = true
            selectPrimaryIfUnselected()
        }

        val isInitFailure = !primaryReadyObserved && when (engineStatus) {
            TranscriptionEngineStatus.ModelUnavailable,
            TranscriptionEngineStatus.NativeLibraryUnavailable,
            TranscriptionEngineStatus.Unavailable,
            TranscriptionEngineStatus.Error -> true
            else -> false
        }

        if (isInitFailure) {
            activateFallback(primaryFailureFor(engineStatus))
            return
        }

        if (activeTranscriber === primaryTranscriber || activeTranscriber == null) {
            _status.value = engineStatus
        }
        recomputeDiagnostics()
    }

    private suspend fun selectPrimaryIfUnselected() {
        selectionMutex.withLock {
            if (activeTranscriber != null) return
            activeTranscriber = primaryTranscriber
            _activeBackend.value = primaryTranscriber.activeBackend.value
            _status.value = primaryTranscriber.status.value
            recomputeDiagnostics()
        }
    }

    private suspend fun activateFallback(reason: TranscriptionFailure) {
        selectionMutex.withLock {
            if (activeTranscriber === fallbackTranscriber) return
            fallbackReason = reason
            primaryTranscriber.stop()
            fallbackTranscriber.start()
            activeTranscriber = fallbackTranscriber
            _activeBackend.value = fallbackTranscriber.activeBackend.value
            _status.value = fallbackTranscriber.status.value
            recomputeDiagnostics()
        }
    }

    private fun primaryFailureFor(status: TranscriptionEngineStatus): TranscriptionFailure = when (status) {
        TranscriptionEngineStatus.ModelUnavailable -> TranscriptionFailure(
            code = "primary-model-unavailable",
            message = buildString {
                append(primaryBackendLabel())
                append(" model file missing")
                primaryDiagnostics.modelPath?.let {
                    append(" at ")
                    append(it)
                }
                append(" — using Android fallback")
            },
        )

        TranscriptionEngineStatus.NativeLibraryUnavailable -> TranscriptionFailure(
            code = "primary-native-library-unavailable",
            message = buildString {
                append(primaryBackendLabel())
                append(" native library failed to load")
                primaryDiagnostics.nativeLibraryLoadError?.let {
                    append(": ")
                    append(it)
                }
                append(" — using Android fallback")
            },
        )

        TranscriptionEngineStatus.Unavailable -> TranscriptionFailure(
            code = "primary-unavailable",
            message = "${primaryBackendLabel()} is unavailable on this device — using Android fallback",
        )

        TranscriptionEngineStatus.Error -> primaryDiagnostics.lastTranscriptError?.copy(
            message = "${primaryDiagnostics.lastTranscriptError?.message ?: "Primary backend init failed"} — using Android fallback"
        ) ?: TranscriptionFailure(
            code = "primary-init-error",
            message = "${primaryBackendLabel()} init failed — using Android fallback",
        )

        else -> TranscriptionFailure(
            code = "primary-fallback",
            message = "${primaryBackendLabel()} could not start — using Android fallback",
        )
    }

    private fun primaryBackendLabel(): String = when (primaryDiagnostics.selectedBackend) {
        TranscriptionBackend.RemoteRealtime -> "Realtime backend"
        TranscriptionBackend.WhisperCpp -> "Whisper"
        TranscriptionBackend.DedicatedLocalStt -> "Vosk"
        TranscriptionBackend.AndroidSpeechRecognizer -> "Android recognizer"
        TranscriptionBackend.None -> "Primary backend"
    }

    private fun recomputeDiagnostics() {
        val activeBackendSnapshot = when (activeTranscriber) {
            primaryTranscriber -> primaryDiagnostics.activeBackend
            fallbackTranscriber -> fallbackDiagnostics.activeBackend
            else -> TranscriptionBackend.None
        }
        val activeStatusSnapshot = when (activeTranscriber) {
            primaryTranscriber -> primaryDiagnostics.selectedBackendStatus
            fallbackTranscriber -> fallbackDiagnostics.selectedBackendStatus
            else -> _status.value
        }
        val lastError = when (activeTranscriber) {
            fallbackTranscriber -> fallbackDiagnostics.lastTranscriptError ?: fallbackReason ?: primaryDiagnostics.lastTranscriptError
            primaryTranscriber -> primaryDiagnostics.lastTranscriptError
            else -> fallbackReason ?: primaryDiagnostics.lastTranscriptError ?: fallbackDiagnostics.lastTranscriptError
        }
        _diagnostics.value = TranscriptionDiagnostics(
            selectedBackend = primaryDiagnostics.selectedBackend,
            activeBackend = activeBackendSnapshot,
            selectedModelId = primaryDiagnostics.selectedModelId,
            selectedModelDisplayName = primaryDiagnostics.selectedModelDisplayName,
            activeModelId = when (activeTranscriber) {
                primaryTranscriber -> primaryDiagnostics.activeModelId
                fallbackTranscriber -> fallbackDiagnostics.activeModelId
                else -> null
            },
            activeModelDisplayName = when (activeTranscriber) {
                primaryTranscriber -> primaryDiagnostics.activeModelDisplayName
                fallbackTranscriber -> fallbackDiagnostics.activeModelDisplayName
                else -> null
            },
            selectedBackendStatus = primaryDiagnostics.selectedBackendStatus,
            activeBackendStatus = activeStatusSnapshot,
            fallbackBackendStatus = fallbackDiagnostics.selectedBackendStatus,
            fallbackActive = activeTranscriber === fallbackTranscriber,
            fallbackReason = fallbackReason,
            modelPath = primaryDiagnostics.modelPath,
            modelFilePresent = primaryDiagnostics.modelFilePresent,
            modelFileReadable = primaryDiagnostics.modelFileReadable,
            modelFileSizeBytes = primaryDiagnostics.modelFileSizeBytes,
            nativeLibraryName = primaryDiagnostics.nativeLibraryName,
            nativeLibraryLoaded = primaryDiagnostics.nativeLibraryLoaded,
            nativeLibraryLoadError = primaryDiagnostics.nativeLibraryLoadError,
            nativeInitAttempted = primaryDiagnostics.nativeInitAttempted,
            nativeInitContextPointer = primaryDiagnostics.nativeInitContextPointer,
            selectedBackendInitSucceeded = primaryDiagnostics.selectedBackendInitSucceeded,
            selectedBackendReady = primaryDiagnostics.selectedBackendReady,
            audioSourceAttached = primaryDiagnostics.audioSourceAttached,
            selectedBackendAudioFramesReceived = primaryDiagnostics.selectedBackendAudioFramesReceived,
            selectedBackendBufferedSamples = primaryDiagnostics.selectedBackendBufferedSamples,
            chunkDurationMs = primaryDiagnostics.chunkDurationMs,
            chunkOverlapMs = primaryDiagnostics.chunkOverlapMs,
            chunksProcessed = primaryDiagnostics.chunksProcessed,
            selectedBackendTranscriptUpdatesEmitted = primaryDiagnostics.selectedBackendTranscriptUpdatesEmitted,
            fallbackTranscriptUpdatesEmitted = fallbackDiagnostics.selectedBackendTranscriptUpdatesEmitted,
            totalTranscriptUpdatesEmitted =
                primaryDiagnostics.selectedBackendTranscriptUpdatesEmitted +
                    fallbackDiagnostics.selectedBackendTranscriptUpdatesEmitted,
            audioInputSampleRateHz = primaryDiagnostics.audioInputSampleRateHz,
            audioOutputSampleRateHz = primaryDiagnostics.audioOutputSampleRateHz,
            audioResampledToTarget = primaryDiagnostics.audioResampledToTarget,
            audioPeakAbsAmplitude = primaryDiagnostics.audioPeakAbsAmplitude,
            audioAverageAbsAmplitude = primaryDiagnostics.audioAverageAbsAmplitude,
            audioClippedSampleCount = primaryDiagnostics.audioClippedSampleCount,
            audioDurationMs = primaryDiagnostics.audioDurationMs,
            timeToFirstTranscriptMs = primaryDiagnostics.timeToFirstTranscriptMs,
            timeToFirstFinalLikeUpdateMs = primaryDiagnostics.timeToFirstFinalLikeUpdateMs,
            averageChunkInferenceLatencyMs = primaryDiagnostics.averageChunkInferenceLatencyMs,
            totalProcessingTimeMs = primaryDiagnostics.totalProcessingTimeMs,
            lastTranscriptSource = lastTranscriptSource,
            lastTranscriptError = lastError,
            lastSuccessfulTranscriptAtMs = mergeLatestTimestamp(
                mergeLatestTimestamp(
                    primaryDiagnostics.lastSuccessfulTranscriptAtMs,
                    fallbackDiagnostics.lastSuccessfulTranscriptAtMs,
                ),
                lastSuccessfulTranscriptAtMs,
            ),
        )
    }

    companion object {
        /**
         * Time to wait (ms) after starting the primary backend before checking its status
         * to decide whether fallback is needed.
         */
        internal const val FALLBACK_CHECK_DELAY_MS = 300L
    }
}
