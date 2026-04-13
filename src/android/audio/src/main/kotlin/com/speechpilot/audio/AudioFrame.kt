package com.speechpilot.audio

data class AudioFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val capturedAtMs: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return sampleRate == other.sampleRate &&
            capturedAtMs == other.capturedAtMs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + capturedAtMs.hashCode()
        return result
    }
}
