package com.speechpilot.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultSessionManager : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveState = MutableStateFlow(LiveSessionState())
    override val liveState: StateFlow<LiveSessionState> = _liveState.asStateFlow()

    override suspend fun start(mode: SessionMode) {
        _state.value = SessionState.Starting
        _liveState.value = LiveSessionState(sessionState = SessionState.Active, mode = mode, isListening = true)
        _state.value = SessionState.Active
    }

    override suspend fun stop() {
        _state.value = SessionState.Stopping
        _liveState.value = LiveSessionState(sessionState = SessionState.Idle)
        _state.value = SessionState.Idle
    }
}
