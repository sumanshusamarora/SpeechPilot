package com.speechpilot.vad

import com.speechpilot.audio.AudioFrame
import kotlin.math.sqrt

class EnergyBasedVad(
    private val threshold: Double = DEFAULT_THRESHOLD
) : VoiceActivityDetector {

    data class Reading(
        val rms: Double,
        val threshold: Double,
        val result: VadResult
    )

    val configuredThreshold: Double
        get() = threshold

    override fun detect(frame: AudioFrame): VadResult {
        return inspect(frame).result
    }

    fun inspect(frame: AudioFrame): Reading {
        val rms = computeRms(frame.samples)
        val result = if (rms >= threshold) VadResult.Speech else VadResult.Silence
        return Reading(rms = rms, threshold = threshold, result = result)
    }

    private fun computeRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        val sum = samples.fold(0.0) { acc, s -> acc + s.toLong() * s }
        return sqrt(sum / samples.size)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 750.0
    }
}
