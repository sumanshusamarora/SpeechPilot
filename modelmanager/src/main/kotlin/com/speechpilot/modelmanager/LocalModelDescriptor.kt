package com.speechpilot.modelmanager

/**
 * Describes an on-device AI model that the app can automatically provision.
 *
 * All install paths are relative to the app's private files directory
 * ([android.content.Context.getFilesDir]). Adding a new model to the registry requires only a
 * new [LocalModelDescriptor] instance in [KnownModels] — no changes to provisioning logic.
 *
 * @param id Unique, stable string identifier for this model (e.g. `"vosk-model-small-en-us"`).
 *   Used as the key in [LocalModelManager] and must not change across releases.
 * @param type Broad model category ([ModelType]).
 * @param purpose Human-readable description of what this model does.
 * @param downloadUrl HTTPS URL of the model archive (`.zip`).
 * @param installDirName Directory name under `filesDir` where the model will be installed
 *   (e.g. `"vosk-model-small-en-us"`).
 * @param archiveRootDir The single top-level directory name inside the zip archive. Its contents
 *   are extracted directly into [installDirName], stripping the root prefix to avoid nesting.
 * @param version Opaque version string used for display and future update checks.
 * @param sha256 Optional SHA-256 hex digest of the archive for integrity verification. If `null`,
 *   checksum verification is skipped.
 */
data class LocalModelDescriptor(
    val id: String,
    val type: ModelType,
    val purpose: String,
    val downloadUrl: String,
    val installDirName: String,
    val archiveRootDir: String,
    val version: String,
    val sha256: String? = null,
)
