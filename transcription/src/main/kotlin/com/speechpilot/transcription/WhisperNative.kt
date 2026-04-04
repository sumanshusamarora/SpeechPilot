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
 * The native library is compiled automatically via CMake FetchContent during
 * `./gradlew assembleDebug`. CMakeLists.txt in `src/main/cpp/` fetches whisper.cpp v1.7.2
 * and builds it as `libwhisper_jni.so` (the JNI bridge target is named `whisper_jni`).
 *
 * The resulting `.so` files are packaged by the Gradle build into:
 * ```
 * transcription/build/.../jni/arm64-v8a/libwhisper_jni.so
 * transcription/build/.../jni/x86_64/libwhisper_jni.so
 * ```
 * No manual setup is required after the first build.
 */
object WhisperNative {

    const val LIBRARY_NAME = "whisper_jni"

    /**
     * `true` if `libwhisper_jni.so` was loaded successfully at startup.
     *
     * When `false`, none of the [external] functions below can be called — the
     * [WhisperCppLocalTranscriber] will report
     * [TranscriptionEngineStatus.NativeLibraryUnavailable].
     *
     * A `false` value means `System.loadLibrary("whisper_jni")` threw [UnsatisfiedLinkError],
     * which happens when:
     * - The native library was not bundled (e.g. unit-test builds without native compilation), or
     * - The ABI of the device/emulator is not included in the build filters.
     */
    var isAvailable: Boolean = false
        private set

    var loadErrorMessage: String? = null
        private set

    init {
        isAvailable = try {
            System.loadLibrary(LIBRARY_NAME)
            loadErrorMessage = null
            true
        } catch (error: UnsatisfiedLinkError) {
            loadErrorMessage = error.message ?: error.javaClass.simpleName
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
