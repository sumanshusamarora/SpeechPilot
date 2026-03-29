package com.speechpilot.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val state: StateFlow<SessionState>
    val liveState: StateFlow<LiveSessionState>
    suspend fun start()
    suspend fun stop()
}
