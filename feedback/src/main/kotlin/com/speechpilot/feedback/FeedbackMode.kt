package com.speechpilot.feedback

/**
 * Supported feedback output modes.
 *
 * Only [Vibration] is implemented in Phase 1.
 * Additional modes (tone, watch haptic, etc.) may be added later behind the same
 * [FeedbackDispatcher] abstraction.
 */
enum class FeedbackMode {
    /** Deliver feedback as a subtle device vibration pulse. */
    Vibration
}
