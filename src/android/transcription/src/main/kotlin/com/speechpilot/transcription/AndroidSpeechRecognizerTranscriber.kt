package com.speechpilot.transcription

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fallback transcriber built on Android's [SpeechRecognizer].
 *
 * This is the **compatibility / fallback** backend. It is used when the preferred dedicated
 * on-device STT backend ([VoskLocalTranscriber]) is unavailable or its model assets have not
 * been placed on the device.
 *
 * `EXTRA_PREFER_OFFLINE=true` is requested, but recognition quality and offline availability
 * remain recognition-service dependent and vary by device. This is the fundamental reason the
 * dedicated Vosk backend is preferred for reliable local transcription.
 */
class AndroidSpeechRecognizerTranscriber(
    context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : LocalTranscriber {

    private val backend = TranscriptionBackend.AndroidSpeechRecognizer

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

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
            selectedBackend = backend,
            activeBackend = backend,
        )
    )
    override val diagnostics: StateFlow<TranscriptionDiagnostics> = _diagnostics.asStateFlow()

    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(backend).asStateFlow()

    @Volatile
    private var shouldListen = false
    private var recognizer: SpeechRecognizer? = null

    override fun setAudioSource(frames: Flow<com.speechpilot.audio.AudioFrame>) {
        _diagnostics.value = _diagnostics.value.copy(audioSourceAttached = true)
    }

    override suspend fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _status.value = TranscriptionEngineStatus.Unavailable
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = TranscriptionEngineStatus.Unavailable,
                activeBackendStatus = TranscriptionEngineStatus.Unavailable,
                lastTranscriptError = TranscriptionFailure(
                    code = "android-recognizer-unavailable",
                    message = "Android SpeechRecognizer is not available on this device",
                ),
            )
            return
        }
        shouldListen = true
        _diagnostics.value = _diagnostics.value.copy(
            selectedBackendStatus = TranscriptionEngineStatus.Listening,
            activeBackendStatus = TranscriptionEngineStatus.Listening,
            selectedBackendInitSucceeded = true,
            selectedBackendTranscriptUpdatesEmitted = 0,
            totalTranscriptUpdatesEmitted = 0,
            lastTranscriptSource = TranscriptionBackend.None,
            lastTranscriptError = null,
            lastSuccessfulTranscriptAtMs = null,
        )
        withContext(mainDispatcher) {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(Listener())
                }
            }
            _status.value = TranscriptionEngineStatus.Listening
            recognizer?.startListening(recognizerIntent())
        }
    }

    override suspend fun stop() {
        shouldListen = false
        withContext(mainDispatcher) {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            _status.value = TranscriptionEngineStatus.Disabled
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = TranscriptionEngineStatus.Disabled,
                activeBackendStatus = TranscriptionEngineStatus.Disabled,
            )
        }
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun emitTranscript(text: String, stability: TranscriptStability) {
        scope.launch {
            val timestamp = clockMs()
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendTranscriptUpdatesEmitted =
                    _diagnostics.value.selectedBackendTranscriptUpdatesEmitted + 1,
                totalTranscriptUpdatesEmitted = _diagnostics.value.totalTranscriptUpdatesEmitted + 1,
                lastTranscriptSource = backend,
                lastSuccessfulTranscriptAtMs = timestamp,
                lastTranscriptError = null,
            )
            _updates.emit(
                TranscriptUpdate(
                    text = text,
                    stability = stability,
                    receivedAtMs = timestamp
                )
            )
        }
    }

    private fun restartListening() {
        if (!shouldListen) return
        scope.launch {
            _status.value = TranscriptionEngineStatus.Restarting
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = TranscriptionEngineStatus.Restarting,
                activeBackendStatus = TranscriptionEngineStatus.Restarting,
            )
            withContext(mainDispatcher) {
                recognizer?.cancel()
                recognizer?.startListening(recognizerIntent())
                _status.value = TranscriptionEngineStatus.Listening
                _diagnostics.value = _diagnostics.value.copy(
                    selectedBackendStatus = TranscriptionEngineStatus.Listening,
                    activeBackendStatus = TranscriptionEngineStatus.Listening,
                )
            }
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) emitTranscript(text, TranscriptStability.Partial)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) emitTranscript(text, TranscriptStability.Final)
            restartListening()
        }

        override fun onError(error: Int) {
            if (!shouldListen) return
            _status.value = TranscriptionEngineStatus.Error
            _diagnostics.value = _diagnostics.value.copy(
                selectedBackendStatus = TranscriptionEngineStatus.Error,
                activeBackendStatus = TranscriptionEngineStatus.Error,
                lastTranscriptError = TranscriptionFailure(
                    code = speechRecognizerErrorCode(error),
                    message = speechRecognizerErrorMessage(error),
                ),
            )
            restartListening()
        }
    }

    private fun speechRecognizerErrorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "android-error-audio"
        SpeechRecognizer.ERROR_CLIENT -> "android-error-client"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "android-error-permission"
        SpeechRecognizer.ERROR_NETWORK -> "android-error-network"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "android-error-network-timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "android-error-no-match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "android-error-busy"
        SpeechRecognizer.ERROR_SERVER -> "android-error-server"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "android-error-speech-timeout"
        else -> "android-error-$error"
    }

    private fun speechRecognizerErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Android SpeechRecognizer audio capture error"
        SpeechRecognizer.ERROR_CLIENT -> "Android SpeechRecognizer client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Android SpeechRecognizer missing permission"
        SpeechRecognizer.ERROR_NETWORK -> "Android SpeechRecognizer network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Android SpeechRecognizer network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "Android SpeechRecognizer found no match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Android SpeechRecognizer is busy"
        SpeechRecognizer.ERROR_SERVER -> "Android SpeechRecognizer server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Android SpeechRecognizer timed out waiting for speech"
        else -> "Android SpeechRecognizer error code $error"
    }
}
