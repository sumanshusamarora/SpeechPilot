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
 * @param displayName Short user-facing model name shown in the UI (e.g. `"Vosk small (en-US)"`).
 * @param approxSizeMb Approximate download size in megabytes. Shown to the user before download.
 * @param wifiRecommended Whether the download is large enough to recommend a Wi-Fi connection.
 * @param downloadUrl HTTPS URL of the model download (`.zip` archive or single binary file).
 * @param installDirName Directory name under `filesDir` where the model will be installed
 *   (e.g. `"vosk-model-small-en-us"`).
 * @param archiveFormat How the model is packaged. [ModelArchiveFormat.ZIP] requires extraction;
 *   [ModelArchiveFormat.SINGLE_FILE] is placed directly in [installDirName].
 * @param archiveRootDir For [ModelArchiveFormat.ZIP] only: the single top-level directory name
 *   inside the zip archive. Its contents are extracted into [installDirName], stripping the root
 *   prefix. Ignored for [ModelArchiveFormat.SINGLE_FILE] models.
 * @param singleFileName For [ModelArchiveFormat.SINGLE_FILE] only: the name of the binary file
 *   that will be written to `filesDir/[installDirName]/[singleFileName]`. Ignored for
 *   [ModelArchiveFormat.ZIP] models.
 * @param version Opaque version string used for display and future update checks.
 * @param sha256 Optional SHA-256 hex digest of the downloaded file for integrity verification.
 *   If `null`, checksum verification is skipped.
 */
data class LocalModelDescriptor(
    val id: String,
    val type: ModelType,
    val purpose: String,
    val displayName: String,
    val approxSizeMb: Int,
    val wifiRecommended: Boolean,
    val downloadUrl: String,
    val installDirName: String,
    val archiveRootDir: String,
    val version: String,
    val sha256: String? = null,
    val archiveFormat: ModelArchiveFormat = ModelArchiveFormat.ZIP,
    val singleFileName: String = "",
)
