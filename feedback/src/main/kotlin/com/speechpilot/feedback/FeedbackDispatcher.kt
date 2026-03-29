package com.speechpilot.feedback

/**
 * Executes a [FeedbackEvent] through a specific output channel (vibration, tone, etc.).
 *
 * Decouples feedback delivery from decision logic, making the output channel swappable
 * without touching session or decisioning code.
 */
interface FeedbackDispatcher {
    /**
     * Deliver the given [event] to the user.
     *
     * Implementations must be safe to call from any thread.
     * Implementations must not throw; they should handle unsupported hardware gracefully.
     */
    fun dispatch(event: FeedbackEvent)
}
