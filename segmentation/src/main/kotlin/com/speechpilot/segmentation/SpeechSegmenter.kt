package com.speechpilot.segmentation

import com.speechpilot.audio.AudioFrame
import kotlinx.coroutines.flow.Flow

interface SpeechSegmenter {
    fun segment(frames: Flow<AudioFrame>): Flow<SpeechSegment>
}
