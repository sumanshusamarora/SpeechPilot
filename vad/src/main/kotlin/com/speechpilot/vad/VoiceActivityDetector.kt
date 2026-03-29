package com.speechpilot.vad

import com.speechpilot.audio.AudioFrame

interface VoiceActivityDetector {
    fun detect(frame: AudioFrame): VadResult
}
