/**
 * whisper_jni.cpp — JNI bridge between WhisperNative.kt and whisper.cpp
 *
 * Each function signature must exactly match the `external` declaration in
 * com.speechpilot.transcription.WhisperNative. The naming convention is:
 *
 *   Java_<package_dots_replaced_by_underscores>_<ClassName>_<methodName>
 *
 * This file wraps the public whisper.cpp C API (whisper.h) and handles:
 *  - Model initialization from a file path
 *  - Full (non-streaming) transcription of a float PCM buffer
 *  - Segment enumeration
 *  - Context cleanup
 *
 * All allocations from JNI (GetStringUTFChars, GetFloatArrayElements) are
 * explicitly released before returning to avoid native memory leaks.
 */

#include <jni.h>
#include <string>
#include <android/log.h>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// whisperInit(modelPath: String): Long
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperInit(
        JNIEnv  *env,
        jobject /* obj */,
        jstring  modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("whisperInit: GetStringUTFChars returned null");
        return 0L;
    }

    whisper_context_params params = whisper_context_default_params();
    // GPU acceleration is not requested here; ggml will use CPU NEON on arm64.
    params.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisperInit: failed to load model from '%s'", path);
        return 0L;
    }

    LOGI("whisperInit: context initialised %p", static_cast<void *>(ctx));
    return reinterpret_cast<jlong>(ctx);
}

// ─────────────────────────────────────────────────────────────────────────────
// whisperFull(ctx: Long, samples: FloatArray, nSamples: Int): Int
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperFull(
        JNIEnv   *env,
        jobject  /* obj */,
        jlong     ctxHandle,
        jfloatArray samples,
        jint      nSamples) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (ctx == nullptr) {
        LOGE("whisperFull: null context");
        return -1;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language        = "en";
    params.translate       = false;
    params.no_context      = true;
    params.single_segment  = false;
    params.print_progress  = false;
    params.print_timestamps = false;
    params.print_realtime  = false;
    params.print_special   = false;
    // Thread count: let whisper choose based on available cores.
    params.n_threads       = 4;

    jfloat *raw = env->GetFloatArrayElements(samples, nullptr);
    if (raw == nullptr) {
        LOGE("whisperFull: GetFloatArrayElements returned null");
        return -1;
    }

    const int result = whisper_full(ctx, params, raw, static_cast<int>(nSamples));
    env->ReleaseFloatArrayElements(samples, raw, JNI_ABORT);

    if (result != 0) {
        LOGE("whisperFull: inference returned error %d", result);
    }
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// whisperFullNSegments(ctx: Long): Int
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jint JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperFullNSegments(
        JNIEnv  * /* env */,
        jobject   /* obj */,
        jlong     ctxHandle) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (ctx == nullptr) return 0;
    return whisper_full_n_segments(ctx);
}

// ─────────────────────────────────────────────────────────────────────────────
// whisperFullGetSegmentText(ctx: Long, iSegment: Int): String
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperFullGetSegmentText(
        JNIEnv *env,
        jobject /* obj */,
        jlong   ctxHandle,
        jint    iSegment) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (ctx == nullptr) return env->NewStringUTF("");

    const char *text = whisper_full_get_segment_text(ctx, static_cast<int>(iSegment));
    return env->NewStringUTF(text != nullptr ? text : "");
}

// ─────────────────────────────────────────────────────────────────────────────
// whisperFree(ctx: Long)
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperFree(
        JNIEnv  * /* env */,
        jobject   /* obj */,
        jlong     ctxHandle) {

    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (ctx != nullptr) {
        LOGI("whisperFree: releasing context %p", static_cast<void *>(ctx));
        whisper_free(ctx);
    }
}

} // extern "C"
