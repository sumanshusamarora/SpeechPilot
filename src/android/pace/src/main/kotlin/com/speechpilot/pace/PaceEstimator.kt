package com.speechpilot.pace

import com.speechpilot.segmentation.SpeechSegment

interface PaceEstimator {
    fun estimate(segment: SpeechSegment): PaceMetrics
    fun reset()
}
