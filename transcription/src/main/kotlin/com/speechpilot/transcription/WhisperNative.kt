package com.speechpilot.transcription

/**
 * JNI bridge for the whisper.cpp native library.
 *
 * Attempts to load `libwhisper.so` at class initialization. If the native library is not bundled
 * with the APK (e.g. in CI builds or during unit tests), [isAvailable] will be `false` and all
 * [external] methods must not be called.
 *
 * The function signatures match the whisper.cpp Android JNI example:
 * https://github.com/ggerganov/whisper.cpp/tree/master/examples/whisper.android
 *
 * ## Shipping the native library
 *
 * Pre-compile `whisper.cpp` with the Android NDK and place the resulting `.so` files in:
 * ```
 * transcription/src/main/jniLibs/
 *   arm64-v8a/libwhisper.so
 *   x86_64/libwhisper.so
 * ```
 * The native library is NOT bundled in this repository. Build instructions are available at:
 * https://github.com/ggerganov/whisper.cpp/blob/master/README.md
 */
object WhisperNative {

    /**
     * `true` if `libwhisper.so` was loaded successfully at startup.
     *
     * When `false`, none of the [external] functions below can be called — the
     * [WhisperCppLocalTranscriber] will report [TranscriptionEngineStatus.ModelUnavailable].
     */
    var isAvailable: Boolean = false
        private set

    init {
        isAvailable = try {
            System.loadLibrary("whisper")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Initializes a Whisper context from the given ggml model file.
     *
     * @param modelPath Absolute path to the `.bin` ggml model file on disk.
     * @return A positive context pointer on success, or `0L` / `-1L` on failure.
     *   The pointer must be freed with [whisperFree] when no longer needed.
     */
    external fun whisperInit(modelPath: String): Long

    /**
     * Runs full transcription on a block of 16 kHz mono PCM audio samples.
     *
     * Samples must be normalized to the range [-1.0, 1.0].
     *
     * @param ctx Whisper context pointer returned by [whisperInit].
     * @param samples Normalized mono PCM float array at 16 kHz.
     * @param nSamples Number of valid samples in [samples].
     * @return 0 on success; non-zero on failure.
     */
    external fun whisperFull(ctx: Long, samples: FloatArray, nSamples: Int): Int

    /**
     * Returns the number of transcribed segments produced by the last [whisperFull] call.
     *
     * @param ctx Whisper context pointer.
     */
    external fun whisperFullNSegments(ctx: Long): Int

    /**
     * Returns the text of the [iSegment]-th transcript segment.
     *
     * @param ctx Whisper context pointer.
     * @param iSegment Zero-based segment index; must be less than [whisperFullNSegments].
     */
    external fun whisperFullGetSegmentText(ctx: Long, iSegment: Int): String

    /**
     * Releases all resources held by the Whisper context.
     *
     * The pointer [ctx] must not be used after this call.
     *
     * @param ctx Whisper context pointer returned by [whisperInit].
     */
    external fun whisperFree(ctx: Long)
}
