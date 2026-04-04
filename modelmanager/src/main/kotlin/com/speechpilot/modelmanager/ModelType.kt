package com.speechpilot.modelmanager

/**
 * Broad category of an on-device AI model.
 *
 * Used to organise models in the registry and drives per-type readiness checking in
 * [DefaultLocalModelManager]. Adding support for a new model family (e.g. Gemma LLM) requires
 * adding a new enum constant here and handling it in [DefaultLocalModelManager.isInstalledOnDisk].
 */
enum class ModelType {
    /** Automatic speech recognition / speech-to-text model (e.g. Vosk). */
    STT,

    /** Large-language model for on-device inference (e.g. Gemma). */
    LLM,
}
