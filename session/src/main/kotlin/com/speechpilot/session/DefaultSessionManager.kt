package com.speechpilot.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DefaultSessionManager : SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override suspend fun start() {
        _state.value = SessionState.Starting
        // TODO: wire audio capture, VAD, segmentation, pace, feedback
        _state.value = SessionState.Active
    }

    override suspend fun stop() {
        _state.value = SessionState.Stopping
        // TODO: release audio resources
        _state.value = SessionState.Idle
    }
}
