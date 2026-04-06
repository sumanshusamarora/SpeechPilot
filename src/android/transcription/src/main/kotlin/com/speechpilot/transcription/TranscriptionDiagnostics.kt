package com.speechpilot.transcription

data class TranscriptionFailure(
    val code: String,
    val message: String,
)

data class TranscriptionDiagnostics(
    val selectedBackend: TranscriptionBackend = TranscriptionBackend.None,
    val activeBackend: TranscriptionBackend = TranscriptionBackend.None,
    val selectedModelId: String? = null,
    val selectedModelDisplayName: String? = null,
    val activeModelId: String? = null,
    val activeModelDisplayName: String? = null,
    val selectedBackendStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled,
    val activeBackendStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled,
    val fallbackBackendStatus: TranscriptionEngineStatus = TranscriptionEngineStatus.Disabled,
    val fallbackActive: Boolean = false,
    val fallbackReason: TranscriptionFailure? = null,
    val modelPath: String? = null,
    val modelFilePresent: Boolean = false,
    val modelFileReadable: Boolean = false,
    val modelFileSizeBytes: Long? = null,
    val nativeLibraryName: String? = null,
    val nativeLibraryLoaded: Boolean? = null,
    val nativeLibraryLoadError: String? = null,
    val nativeInitAttempted: Boolean = false,
    val nativeInitContextPointer: Long? = null,
    val selectedBackendInitSucceeded: Boolean = false,
    val selectedBackendReady: Boolean = false,
    val audioSourceAttached: Boolean = false,
    val selectedBackendAudioFramesReceived: Long = 0,
    val selectedBackendBufferedSamples: Int = 0,
    val chunkDurationMs: Long? = null,
    val chunkOverlapMs: Long? = null,
    val chunksProcessed: Int = 0,
    val selectedBackendTranscriptUpdatesEmitted: Int = 0,
    val fallbackTranscriptUpdatesEmitted: Int = 0,
    val totalTranscriptUpdatesEmitted: Int = 0,
    val audioInputSampleRateHz: Int? = null,
    val audioOutputSampleRateHz: Int? = null,
    val audioResampledToTarget: Boolean = false,
    val audioPeakAbsAmplitude: Float = 0f,
    val audioAverageAbsAmplitude: Float = 0f,
    val audioClippedSampleCount: Long = 0L,
    val audioDurationMs: Long = 0L,
    val timeToFirstTranscriptMs: Long? = null,
    val timeToFirstFinalLikeUpdateMs: Long? = null,
    val averageChunkInferenceLatencyMs: Double? = null,
    val totalProcessingTimeMs: Long? = null,
    val lastTranscriptSource: TranscriptionBackend = TranscriptionBackend.None,
    val lastTranscriptError: TranscriptionFailure? = null,
    val lastSuccessfulTranscriptAtMs: Long? = null,
)

internal fun mergeLatestTimestamp(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}
