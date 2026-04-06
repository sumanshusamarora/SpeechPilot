package com.speechpilot.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val state: StateFlow<SessionState>
    val liveState: StateFlow<LiveSessionState>
    /**
     * Starts the audio pipeline and begins a coaching session.
     *
     * @param mode The operational mode for this session. Defaults to [SessionMode.Active].
     *   In [SessionMode.Passive], feedback dispatch is suppressed while the rest of the
     *   pipeline runs normally.
     *
     * Implementations must guard against double-start: calling [start] while already
     * Starting or Active must be a no-op.
     */
    suspend fun start(mode: SessionMode = SessionMode.Active)
    /**
     * Stops the active session and persists the session summary.
     *
     * Implementations must guard against double-stop: calling [stop] while already
     * Idle or Stopping must be a no-op.
     */
    suspend fun stop()

    /** Releases long-lived resources associated with this manager instance. */
    fun release() = Unit
}
