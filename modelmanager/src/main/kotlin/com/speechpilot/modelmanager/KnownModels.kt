package com.speechpilot.modelmanager

import java.io.File

/**
 * Registry of on-device model descriptors recognised by the app.
 *
 * Add a new [LocalModelDescriptor] to [all] to register a new model with [DefaultLocalModelManager].
 * The [LocalModelDescriptor.id] must be stable across releases as it is used as the lookup key.
 */
object KnownModels {

    /**
     * Vosk small English speech-to-text model (~40 MB compressed).
     *
     * Required by the Vosk transcription backend. Must be installed at
     * `filesDir/vosk-model-small-en-us` before the Vosk STT engine can start.
     *
     * Download source: https://alphacephei.com/vosk/models
     */
    val VOSK_SMALL_EN_US = LocalModelDescriptor(
        id = "vosk-model-small-en-us",
        type = ModelType.STT,
        purpose = "On-device English speech recognition (Vosk)",
        displayName = "Vosk small (en-US)",
        approxSizeMb = 40,
        wifiRecommended = false,
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        installDirName = "vosk-model-small-en-us",
        archiveRootDir = "vosk-model-small-en-us-0.15",
        version = "0.15",
        archiveFormat = ModelArchiveFormat.ZIP,
    )

    /**
     * Whisper.cpp ggml-tiny.en English STT model (~75 MB).
     *
     * This English-only model is fast enough for live mobile transcription while still providing
     * usable transcript quality for pace coaching.
     *
     * Installed at: `filesDir/whisper/ggml-tiny.en.bin`
     *
     * Download source: https://huggingface.co/ggerganov/whisper.cpp
     */
    val WHISPER_GGML_TINY_EN = LocalModelDescriptor(
        id = "whisper-ggml-tiny-en",
        type = ModelType.STT,
        purpose = "On-device English speech recognition (Whisper.cpp, ggml-tiny.en)",
        displayName = "Whisper tiny.en (ggml)",
        approxSizeMb = 75,
        wifiRecommended = false,
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
        installDirName = "whisper",
        archiveRootDir = "",
        singleFileName = "ggml-tiny.en.bin",
        version = "ggml-tiny.en",
        archiveFormat = ModelArchiveFormat.SINGLE_FILE,
    )

    /**
     * Backwards-compatible alias used by older code/tests that still reference the previous
     * Whisper descriptor symbol.
     */
    @Deprecated(
        message = "Use WHISPER_GGML_TINY_EN",
        replaceWith = ReplaceWith("WHISPER_GGML_TINY_EN")
    )
    val WHISPER_GGML_SMALL: LocalModelDescriptor = WHISPER_GGML_TINY_EN

    /**
     * Returns the Whisper model file to use at runtime.
     *
     * Preference order:
     * 1. `ggml-tiny.en.bin` (new default, suitable for live mobile transcription)
     * 2. `ggml-small.bin` (legacy installs only)
     * 3. `ggml-tiny.en.bin` target path (when provisioning is still pending)
     */
    fun preferredWhisperModelFile(filesDir: File): File {
        val installDir = File(filesDir, WHISPER_GGML_TINY_EN.installDirName)
        val preferred = File(installDir, WHISPER_GGML_TINY_EN.singleFileName)
        val legacy = File(installDir, LEGACY_WHISPER_GGML_SMALL_FILE_NAME)
        return when {
            preferred.isFile -> preferred
            legacy.isFile -> legacy
            else -> preferred
        }
    }

    // Future: Gemma 4 E2B (LLM) — uncomment and fill in when implementing Gemma support.
    // val GEMMA_4_E2B = LocalModelDescriptor(
    //     id = "gemma-4-e2b",
    //     type = ModelType.LLM,
    //     purpose = "On-device language model (Gemma 4 E2B)",
    //     downloadUrl = "...",
    //     installDirName = "gemma-4-e2b",
    //     archiveRootDir = "gemma-4-e2b",
    //     version = "4.0",
    //     archiveFormat = ModelArchiveFormat.ZIP,
    // )

    /** All registered model descriptors. Consumed by [DefaultLocalModelManager] at init. */
    val all: List<LocalModelDescriptor> = listOf(VOSK_SMALL_EN_US, WHISPER_GGML_TINY_EN)

    private const val LEGACY_WHISPER_GGML_SMALL_FILE_NAME = "ggml-small.bin"
}
