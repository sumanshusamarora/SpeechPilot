package com.speechpilot.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

class MicrophoneCapture(
    private val sampleRate: Int = SAMPLE_RATE,
    private val frameSize: Int = FRAME_SIZE
) : AudioCapture {

    override var isCapturing: Boolean = false
        private set

    override fun frames(): Flow<AudioFrame> = flow {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, ENCODING),
            frameSize * Short.SIZE_BYTES
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL_CONFIG,
            ENCODING,
            bufferSize
        )
        recorder.startRecording()
        isCapturing = true
        try {
            val buffer = ShortArray(frameSize)
            while (coroutineContext.isActive) {
                val read = recorder.read(buffer, 0, frameSize)
                if (read > 0) {
                    emit(AudioFrame(buffer.copyOf(read), sampleRate, System.currentTimeMillis()))
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            isCapturing = false
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun start() {
        // Managed by the flow collector
    }

    override suspend fun stop() {
        // Cancelling the collection scope stops the flow
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 512
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
