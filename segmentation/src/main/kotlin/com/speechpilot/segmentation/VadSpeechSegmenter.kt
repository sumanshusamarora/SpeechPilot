package com.speechpilot.segmentation

import com.speechpilot.audio.AudioFrame
import com.speechpilot.vad.EnergyBasedVad
import com.speechpilot.vad.VadResult
import com.speechpilot.vad.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class VadFrameClassification { Speech, Silence }

data class SegmentationDebugSnapshot(
    val frameRms: Double,
    val vadThreshold: Double?,
    val frameClassification: VadFrameClassification,
    val isSegmentOpen: Boolean,
    val openSegmentFrameCount: Int,
    val openSegmentSilenceFrameCount: Int,
    val finalizedSegmentsCount: Int
)

class VadSpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val minSilenceFrames: Int = MIN_SILENCE_FRAMES
) : SpeechSegmenter {

    var onDebugSnapshot: ((SegmentationDebugSnapshot) -> Unit)? = null

    val configuredVadThreshold: Double?
        get() = (vad as? EnergyBasedVad)?.configuredThreshold

    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = flow {
        val buffer = mutableListOf<AudioFrame>()
        var silenceCount = 0
        var segmentStartMs = 0L
        var finalizedSegments = 0

        frames.collect { frame ->
            val reading = (vad as? EnergyBasedVad)?.inspect(frame)
            val result = reading?.result ?: vad.detect(frame)
            val rms = reading?.rms ?: 0.0
            val threshold = reading?.threshold

            when (result) {
                VadResult.Speech -> {
                    if (buffer.isEmpty()) segmentStartMs = frame.capturedAtMs
                    buffer.add(frame)
                    silenceCount = 0
                }
                VadResult.Silence -> {
                    if (buffer.isNotEmpty()) {
                        silenceCount++
                        if (silenceCount >= minSilenceFrames) {
                            emit(SpeechSegment(buffer.toList(), segmentStartMs, frame.capturedAtMs))
                            finalizedSegments++
                            buffer.clear()
                            silenceCount = 0
                        }
                    }
                }
            }

            onDebugSnapshot?.invoke(
                SegmentationDebugSnapshot(
                    frameRms = rms,
                    vadThreshold = threshold,
                    frameClassification = if (result == VadResult.Speech) {
                        VadFrameClassification.Speech
                    } else {
                        VadFrameClassification.Silence
                    },
                    isSegmentOpen = buffer.isNotEmpty(),
                    openSegmentFrameCount = buffer.size,
                    openSegmentSilenceFrameCount = silenceCount,
                    finalizedSegmentsCount = finalizedSegments
                )
            )
        }
        if (buffer.isNotEmpty()) {
            val lastMs = buffer.last().capturedAtMs
            emit(SpeechSegment(buffer.toList(), segmentStartMs, lastMs))
        }
    }

    companion object {
        const val MIN_SILENCE_FRAMES = 6
    }
}
