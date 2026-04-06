package com.speechpilot.session

import kotlinx.coroutines.flow.Flow

sealed interface RealtimeSocketEvent {
    data object Open : RealtimeSocketEvent
    data class Message(val text: String) : RealtimeSocketEvent
    data class Closing(val code: Int, val reason: String) : RealtimeSocketEvent
    data class Closed(val code: Int, val reason: String) : RealtimeSocketEvent
    data class Failure(val throwable: Throwable) : RealtimeSocketEvent
}

interface RealtimeWebSocketClient {
    fun connect(url: String): Flow<RealtimeSocketEvent>
    fun send(message: String): Boolean
    fun disconnect(code: Int = 1000, reason: String = "client_stop"): Boolean
    fun release() = Unit
}