package com.speechpilot.vad

import com.speechpilot.audio.AudioFrame
import kotlin.math.sqrt

class EnergyBasedVad(
    private val threshold: Double = DEFAULT_THRESHOLD
) : VoiceActivityDetector {

    override fun detect(frame: AudioFrame): VadResult {
        val rms = computeRms(frame.samples)
        return if (rms >= threshold) VadResult.Speech else VadResult.Silence
    }

    private fun computeRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        val sum = samples.fold(0.0) { acc, s -> acc + s.toLong() * s }
        return sqrt(sum / samples.size)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 300.0
    }
}
