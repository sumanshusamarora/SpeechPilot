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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-oriented local transcriber built on Android's [SpeechRecognizer].
 *
 * This implementation requests offline recognition (`EXTRA_PREFER_OFFLINE=true`) and never
 * makes direct app-level network calls, but availability/quality depend on device speech packs
 * and the platform recognition service.
 */
class AndroidSpeechRecognizerTranscriber(
    context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : LocalTranscriber {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _updates = MutableSharedFlow<TranscriptUpdate>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val updates: Flow<TranscriptUpdate> = _updates.asSharedFlow()

    @Volatile
    private var shouldListen = false
    private var recognizer: SpeechRecognizer? = null

    override suspend fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            emitStatus("speech recognizer unavailable", TranscriptStability.Final)
            return
        }
        shouldListen = true
        withContext(mainDispatcher) {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(Listener())
                }
            }
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
        }
    }

    private fun recognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun emitStatus(text: String, stability: TranscriptStability) {
        scope.launch {
            _updates.emit(
                TranscriptUpdate(
                    text = text,
                    stability = stability,
                    receivedAtMs = clockMs()
                )
            )
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
            if (text.isNotEmpty()) {
                emitStatus(text, TranscriptStability.Partial)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) {
                emitStatus(text, TranscriptStability.Final)
            }
            if (shouldListen) {
                scope.launch {
                    withContext(mainDispatcher) {
                        recognizer?.startListening(recognizerIntent())
                    }
                }
            }
        }

        override fun onError(error: Int) {
            if (!shouldListen) return
            // Emit a lightweight debug hint and continue listening.
            emitStatus("[transcription error: $error]", TranscriptStability.Final)
            scope.launch {
                withContext(mainDispatcher) {
                    recognizer?.cancel()
                    recognizer?.startListening(recognizerIntent())
                }
            }
        }
    }
}
