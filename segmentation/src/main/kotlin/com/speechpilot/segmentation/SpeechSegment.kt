package com.speechpilot.segmentation

import com.speechpilot.audio.AudioFrame

data class SpeechSegment(
    val frames: List<AudioFrame>,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}
