package com.speechpilot.session

/**
 * Describes the operational mode of a coaching session.
 *
 * - [Active]: Full coaching session. Pace estimation and feedback dispatch are enabled.
 *   This is the default mode and the only fully implemented mode in Phase 1.
 *
 * - [Passive]: Lightweight monitoring mode. Audio is captured and speech is detected
 *   but feedback is suppressed. This is an explicit anchor for future passive/always-listen
 *   behaviour. It is **not** a full background daemon in Phase 1 — it simply suppresses
 *   feedback output while the rest of the pipeline runs normally.
 */
enum class SessionMode {
    Active,
    Passive
}
