package com.speechpilot.segmentation

import com.speechpilot.audio.AudioFrame
import com.speechpilot.vad.VadResult
import com.speechpilot.vad.VoiceActivityDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class VadSpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val minSilenceFrames: Int = MIN_SILENCE_FRAMES
) : SpeechSegmenter {

    override fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment> = flow {
        val buffer = mutableListOf<AudioFrame>()
        var silenceCount = 0
        var segmentStartMs = 0L

        frames.collect { frame ->
            when (vad.detect(frame)) {
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
                            buffer.clear()
                            silenceCount = 0
                        }
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) {
            val lastMs = buffer.last().capturedAtMs
            emit(SpeechSegment(buffer.toList(), segmentStartMs, lastMs))
        }
    }

    companion object {
        const val MIN_SILENCE_FRAMES = 10
    }
}
