package com.speechpilot.session

sealed class SessionState {
    data object Idle : SessionState()
    data object Starting : SessionState()
    data object Active : SessionState()
    data object Stopping : SessionState()
    data class Error(val cause: Throwable) : SessionState()
}
