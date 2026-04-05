# CMakeLists.txt — Whisper.cpp JNI bridge for SpeechPilot
#
# This file builds libwhisper_jni.so, which exposes whisper.cpp inference to Kotlin via
# the JNI declarations in WhisperNative.kt.
#
# ## How the native library is obtained
#
# whisper.cpp is fetched from GitHub via CMake's FetchContent during the first
# `./gradlew assembleDebug` (or any build that triggers the externalNativeBuild task).
# The sources are cached by CMake after the initial download and do not require a separate
# manual setup step.  Network access is needed on the first build only.
#
# ## Supported ABI
#
# arm64-v8a is the primary target (all Android devices released 2017+). x86_64 is included
# for emulator development. Other ABIs (armeabi-v7a, x86) are omitted — their devices are
# old enough that Whisper inference would be impractically slow anyway.
#
# ## Build options
#
# The default build disables optional BLAS/METAL/CUDA acceleration that requires additional
# SDK tooling. Inference runs on the CPU via ggml's optimized NEON kernels on arm64.

cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ──────────────────────────────────────────────────────────────────────────────
# Disable optional acceleration that needs extra tooling.
# ──────────────────────────────────────────────────────────────────────────────
set(WHISPER_NO_METAL      ON  CACHE BOOL "" FORCE)
set(WHISPER_NO_ACCELERATE ON  CACHE BOOL "" FORCE)
set(WHISPER_NO_OPENBLAS   ON  CACHE BOOL "" FORCE)
set(WHISPER_NO_MKL        ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_CUDA             OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

# ──────────────────────────────────────────────────────────────────────────────
# Fetch whisper.cpp from GitHub.
# Pin to a specific tag so builds are reproducible.
# ──────────────────────────────────────────────────────────────────────────────
include(FetchContent)

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.7.2
    GIT_SHALLOW    TRUE
    GIT_PROGRESS   TRUE
)

FetchContent_MakeAvailable(whisper_cpp)

# Android debug APKs still need optimized native inference. Without this, ggml and whisper
# compile with Debug defaults (`-g` and no `-O*`), which makes live transcription unusably slow.
function(speechpilot_optimize_native_target target_name)
    if (TARGET ${target_name})
        target_compile_options(${target_name} PRIVATE
            -O3
            -DNDEBUG
            -fvisibility=hidden
            -ffunction-sections
            -fdata-sections
        )
        target_link_options(${target_name} PRIVATE
            -Wl,--gc-sections
            -Wl,--exclude-libs,ALL
        )
    endif()
endfunction()

speechpilot_optimize_native_target(ggml)
speechpilot_optimize_native_target(whisper)

# ──────────────────────────────────────────────────────────────────────────────
# JNI bridge shared library.
# Function signatures must match the `external` declarations in WhisperNative.kt.
# ──────────────────────────────────────────────────────────────────────────────
add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${whisper_cpp_SOURCE_DIR}/include
    ${whisper_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(whisper_jni PRIVATE
    whisper
    android
    log
)

target_compile_options(whisper_jni PRIVATE
    -O3
    -DNDEBUG
    -O2
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -Wall
)

target_link_options(whisper_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)
