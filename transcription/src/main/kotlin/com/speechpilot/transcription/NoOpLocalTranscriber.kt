package com.speechpilot.transcription

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class NoOpLocalTranscriber : LocalTranscriber {
    override val updates: Flow<TranscriptUpdate> = emptyFlow()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit
}
