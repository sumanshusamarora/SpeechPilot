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

    private val recorderLock = Any()

    override var isCapturing: Boolean = false
        private set

    @Volatile
    private var activeRecorder: AudioRecord? = null

    @Volatile
    private var stopRequested: Boolean = false

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
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Microphone failed to initialize")
        }
        synchronized(recorderLock) {
            activeRecorder = recorder
        }
        recorder.startRecording()
        isCapturing = true
        try {
            val buffer = ShortArray(frameSize)
            while (coroutineContext.isActive && !stopRequested) {
                val read = recorder.read(buffer, 0, frameSize)
                if (stopRequested) break
                when {
                    read > 0 -> {
                        emit(AudioFrame(buffer.copyOf(read), sampleRate, System.currentTimeMillis()))
                    }
                    read == 0 -> Unit
                    read == AudioRecord.ERROR_DEAD_OBJECT -> break
                    read < 0 -> {
                        throw IllegalStateException("Microphone read failed with code $read")
                    }
                }
            }
        } finally {
            synchronized(recorderLock) {
                if (activeRecorder === recorder) {
                    activeRecorder = null
                }
            }
            safeStop(recorder)
            recorder.release()
            isCapturing = false
            stopRequested = false
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun start() {
        stopRequested = false
    }

    override suspend fun stop() {
        stopRequested = true
        val recorder = synchronized(recorderLock) { activeRecorder }
        safeStop(recorder)
    }

    private fun safeStop(recorder: AudioRecord?) {
        if (recorder == null) return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: IllegalStateException) {
            // The recorder may already be stopping or released from another shutdown path.
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SIZE = 512
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
