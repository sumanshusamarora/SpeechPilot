package com.speechpilot.feedback

import com.speechpilot.pace.PaceMetrics

interface FeedbackDecision {
    fun evaluate(metrics: PaceMetrics): FeedbackEvent?
}
