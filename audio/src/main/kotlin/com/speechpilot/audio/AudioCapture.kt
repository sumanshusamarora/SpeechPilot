package com.speechpilot.audio

import kotlinx.coroutines.flow.Flow

interface AudioCapture {
    val isCapturing: Boolean
    fun frames(): Flow<AudioFrame>
    suspend fun start()
    suspend fun stop()
}
