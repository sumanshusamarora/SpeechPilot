package com.speechpilot.modelmanager

/**
 * Represents the full lifecycle of a local on-device model managed by [LocalModelManager].
 *
 * States progress in the typical happy path as:
 * ```
 * NotInstalled → Queued → Downloading → Unpacking → Verifying → Ready
 * ```
 * [Failed] may follow any active provisioning state. Calling [LocalModelManager.retry] on a
 * failed model resets it to [Queued] and restarts the provisioning flow.
 */
sealed class ModelInstallState {

    /** Model is not installed locally and provisioning has not been requested. */
    data object NotInstalled : ModelInstallState()

    /** Provisioning has been requested and is waiting for the download to begin. */
    data object Queued : ModelInstallState()

    /**
     * Archive is actively downloading.
     *
     * @param progressPercent Download progress in [0, 100], or -1 if the server did not provide
     *   a `Content-Length` header.
     * @param bytesReceived Total bytes received so far.
     * @param totalBytes Total expected archive size in bytes, or -1 if unknown.
     */
    data class Downloading(
        val progressPercent: Int,
        val bytesReceived: Long = 0L,
        val totalBytes: Long = -1L,
    ) : ModelInstallState()

    /** Archive has been downloaded and extraction is in progress. */
    data object Unpacking : ModelInstallState()

    /** Extracted files are being verified (e.g. SHA-256 checksum). */
    data object Verifying : ModelInstallState()

    /** Model is fully installed and ready to use. */
    data object Ready : ModelInstallState()

    /**
     * Provisioning failed at some stage.
     *
     * @param reason Short human-readable explanation suitable for display.
     * @param cause Underlying throwable, if any.
     */
    data class Failed(val reason: String, val cause: Throwable? = null) : ModelInstallState()
}
