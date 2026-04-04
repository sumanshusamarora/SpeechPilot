package com.speechpilot.modelmanager

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
        downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        installDirName = "vosk-model-small-en-us",
        archiveRootDir = "vosk-model-small-en-us-0.15",
        version = "0.15",
        archiveFormat = ModelArchiveFormat.ZIP,
    )

    /**
     * Whisper.cpp ggml-small English STT model (~466 MB).
     *
     * Required by the Whisper.cpp transcription backend. The model is a single binary file
     * downloaded directly from HuggingFace — no extraction step is needed.
     *
     * Installed at: `filesDir/whisper/ggml-small.bin`
     *
     * Download source: https://huggingface.co/ggerganov/whisper.cpp
     */
    val WHISPER_GGML_SMALL = LocalModelDescriptor(
        id = "whisper-ggml-small",
        type = ModelType.STT,
        purpose = "On-device English speech recognition (Whisper.cpp, ggml-small)",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
        installDirName = "whisper",
        archiveRootDir = "",
        singleFileName = "ggml-small.bin",
        version = "ggml-small",
        archiveFormat = ModelArchiveFormat.SINGLE_FILE,
    )

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
    val all: List<LocalModelDescriptor> = listOf(VOSK_SMALL_EN_US, WHISPER_GGML_SMALL)
}
