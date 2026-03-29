package com.speechpilot.session

import kotlinx.coroutines.flow.StateFlow

interface SessionManager {
    val state: StateFlow<SessionState>
    suspend fun start()
    suspend fun stop()
}
