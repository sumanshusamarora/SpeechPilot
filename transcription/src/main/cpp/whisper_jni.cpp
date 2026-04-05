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
#include <android/log.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <sys/stat.h>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

thread_local std::string g_last_error;

void clear_last_error() {
    g_last_error.clear();
}

void set_last_error(const std::string &message) {
    g_last_error = message;
    LOGE("%s", message.c_str());
}

std::string errno_message(const char *path) {
    return std::string(path) + ": " + std::strerror(errno);
}

} // namespace

extern "C" {

// ─────────────────────────────────────────────────────────────────────────────
// whisperInit(modelPath: String): Long
// ─────────────────────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperInit(
        JNIEnv  *env,
        jobject /* obj */,
        jstring  modelPath) {

    clear_last_error();

    if (modelPath == nullptr) {
        set_last_error("whisperInit: model path was null");
        return 0L;
    }

    const char *path_chars = env->GetStringUTFChars(modelPath, nullptr);
    if (path_chars == nullptr) {
        set_last_error("whisperInit: GetStringUTFChars returned null");
        return 0L;
    }

    const std::string path(path_chars);
    env->ReleaseStringUTFChars(modelPath, path_chars);

    if (path.empty()) {
        set_last_error("whisperInit: model path was empty");
        return 0L;
    }

    struct stat model_stat {};
    if (stat(path.c_str(), &model_stat) != 0) {
        set_last_error("whisperInit: unable to stat model file " + errno_message(path.c_str()));
        return 0L;
    }

    if (!S_ISREG(model_stat.st_mode)) {
        set_last_error("whisperInit: model path is not a regular file: " + path);
        return 0L;
    }

    if (model_stat.st_size <= 0) {
        set_last_error("whisperInit: model file is empty: " + path);
        return 0L;
    }

    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (file == nullptr) {
        set_last_error("whisperInit: unable to open model file " + errno_message(path.c_str()));
        return 0L;
    }
    std::fclose(file);

    whisper_context_params params = whisper_context_default_params();
    // GPU acceleration is not requested here; ggml will use CPU NEON on arm64.
    params.use_gpu = false;

    LOGI("whisperInit: attempting init from '%s' (%lld bytes)",
         path.c_str(),
         static_cast<long long>(model_stat.st_size));
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);

    if (ctx == nullptr) {
        set_last_error(
                "whisperInit: whisper_init_from_file_with_params returned null for '" + path +
                "' (" + std::to_string(static_cast<long long>(model_stat.st_size)) + " bytes)");
        return 0L;
    }

    clear_last_error();
    LOGI("whisperInit: context initialised %p", static_cast<void *>(ctx));
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperGetLastError(
        JNIEnv  *env,
        jobject /* obj */) {

    if (g_last_error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT void JNICALL
Java_com_speechpilot_transcription_WhisperNative_whisperClearLastError(
        JNIEnv  * /* env */,
        jobject   /* obj */) {

    clear_last_error();
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

    clear_last_error();

    auto *ctx = reinterpret_cast<whisper_context *>(ctxHandle);
    if (ctx == nullptr) {
        set_last_error("whisperFull: null context");
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
        set_last_error("whisperFull: GetFloatArrayElements returned null");
        return -1;
    }

    const int result = whisper_full(ctx, params, raw, static_cast<int>(nSamples));
    env->ReleaseFloatArrayElements(samples, raw, JNI_ABORT);

    if (result != 0) {
        set_last_error("whisperFull: inference returned error " + std::to_string(result));
    } else {
        clear_last_error();
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
