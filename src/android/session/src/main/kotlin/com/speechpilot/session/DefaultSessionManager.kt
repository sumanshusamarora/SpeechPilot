package com.speechpilot.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight in-memory [SessionManager] implementation used in unit tests.
 *
 * This stub performs no audio capture, pipeline processing, or persistence.
 * It simply transitions through the expected state sequence so tests that
 * exercise session-lifecycle callers do not need the full [SpeechCoachSessionManager].
 *
 * **Not for production use.** Wire [SpeechCoachSessionManager] in the application.
 */
class DefaultSessionManager : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveSessionState())
    override val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    override suspend fun start(mode: SessionMode) {
        _state.value = SessionState.Starting
        _state.value = SessionState.Active
        _liveState.value = LiveSessionState(sessionState = SessionState.Active, mode = mode, isListening = true)
    }

    override suspend fun stop() {
        _state.value = SessionState.Stopping
        _state.value = SessionState.Idle
        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
    }

    override fun release() = Unit
}
