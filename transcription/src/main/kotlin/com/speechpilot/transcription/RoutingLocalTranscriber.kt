package com.speechpilot.transcription

import com.speechpilot.audio.AudioFrame
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

/**
 * Routes between the preferred dedicated STT backend and the Android SpeechRecognizer fallback.
 *
 * Selection strategy (evaluated on [start]):
 * 1. Try the [primaryTranscriber] (Vosk-based on-device STT).
 * 2. If it reports [TranscriptionEngineStatus.ModelUnavailable], fall back to [fallbackTranscriber]
 *    (Android SpeechRecognizer).
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

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    private val _status = MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val status: StateFlow<TranscriptionEngineStatus> = _status.asStateFlow()

    private val _activeBackend = MutableStateFlow(TranscriptionBackend.None)
    override val activeBackend: StateFlow<TranscriptionBackend> = _activeBackend.asStateFlow()

    private var routingJob: Job? = null
    private var activeTranscriber: LocalTranscriber? = null

    override fun setAudioSource(frames: Flow<AudioFrame>) {
        primaryTranscriber.setAudioSource(frames)
        fallbackTranscriber.setAudioSource(frames)
    }

    override suspend fun start() {
        if (routingJob?.isActive == true) return
        _status.value = TranscriptionEngineStatus.InitializingModel
        _activeBackend.value = TranscriptionBackend.None

        routingJob = scope.launch {
            // Start the primary backend and observe its initial status.
            primaryTranscriber.start()

            // Wait briefly for the primary backend to report its availability.
            // VoskLocalTranscriber updates status synchronously in start() when model is absent,
            // but the coroutine launched inside may not have run yet. A short delay is sufficient.
            kotlinx.coroutines.delay(fallbackDelayMs)

            val selectedTranscriber = if (
                primaryTranscriber.status.value == TranscriptionEngineStatus.ModelUnavailable
            ) {
                // Primary unavailable — stop it and switch to fallback.
                primaryTranscriber.stop()
                fallbackTranscriber.start()
                _activeBackend.value = fallbackTranscriber.activeBackend.value
                fallbackTranscriber
            } else {
                _activeBackend.value = primaryTranscriber.activeBackend.value
                primaryTranscriber
            }

            activeTranscriber = selectedTranscriber

            // Forward status and updates from the selected backend.
            launch {
                selectedTranscriber.status.collect { engineStatus ->
                    _status.value = engineStatus
                }
            }
            launch {
                selectedTranscriber.updates.collect { update ->
                    _updates.emit(update)
                }
            }
        }
    }

    override suspend fun stop() {
        routingJob?.cancelAndJoin()
        routingJob = null
        val current = activeTranscriber
        activeTranscriber = null
        current?.stop()
        // Also stop the primary if it was started but not yet selected (edge case on rapid stop).
        if (current !== primaryTranscriber) {
            primaryTranscriber.stop()
        }
        _status.value = TranscriptionEngineStatus.Disabled
        _activeBackend.value = TranscriptionBackend.None
    }

    companion object {
        /**
         * Time to wait (ms) after starting the primary backend before checking its status
         * to decide whether fallback is needed.
         */
        internal const val FALLBACK_CHECK_DELAY_MS = 300L
    }
}
