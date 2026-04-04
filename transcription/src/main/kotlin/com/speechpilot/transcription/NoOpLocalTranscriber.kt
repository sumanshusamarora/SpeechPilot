package com.speechpilot.transcription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow

class NoOpLocalTranscriber : LocalTranscriber {
    override val updates: Flow<TranscriptUpdate> = emptyFlow()
    override val status: StateFlow<TranscriptionEngineStatus> =
        MutableStateFlow(TranscriptionEngineStatus.Disabled)
    override val diagnostics: StateFlow<TranscriptionDiagnostics> =
        MutableStateFlow(TranscriptionDiagnostics())
    override val activeBackend: StateFlow<TranscriptionBackend> =
        MutableStateFlow(TranscriptionBackend.None)

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}
