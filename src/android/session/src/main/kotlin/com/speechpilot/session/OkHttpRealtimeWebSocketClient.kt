package com.speechpilot.session

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class OkHttpRealtimeWebSocketClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) : RealtimeWebSocketClient {

    @Volatile
    private var activeSocket: WebSocket? = null

    override fun connect(url: String): Flow<RealtimeSocketEvent> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                activeSocket = webSocket
                trySend(RealtimeSocketEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(RealtimeSocketEvent.Message(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                trySend(RealtimeSocketEvent.Closing(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeSocket === webSocket) {
                    activeSocket = null
                }
                trySend(RealtimeSocketEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeSocket === webSocket) {
                    activeSocket = null
                }
                trySend(RealtimeSocketEvent.Failure(t))
                close(t)
            }
        }

        val socket = okHttpClient.newWebSocket(request, listener)
        activeSocket = socket

        awaitClose {
            if (activeSocket === socket) {
                activeSocket = null
            }
            socket.cancel()
        }
    }

    override fun send(message: String): Boolean = activeSocket?.send(message) ?: false

    override fun disconnect(code: Int, reason: String): Boolean = activeSocket?.close(code, reason) ?: false

    override fun release() {
        activeSocket?.cancel()
        activeSocket = null
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
}