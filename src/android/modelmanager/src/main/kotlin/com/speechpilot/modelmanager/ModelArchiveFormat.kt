package com.speechpilot.modelmanager

/**
 * Describes how a model is packaged for download and installation.
 *
 * The provisioning flow in [DefaultLocalModelManager] varies based on this value:
 * - [ZIP]: download archive, extract contents, verify directory layout.
 * - [SINGLE_FILE]: download a single binary file, place it in the install directory.
 *
 * Whisper ggml models are distributed as single `.bin` files and do not require extraction.
 * Vosk models are distributed as zip archives containing a directory tree.
 */
enum class ModelArchiveFormat {

    /**
     * Model is packaged as a zip archive.
     *
     * The archive is downloaded, extracted using [LocalModelDescriptor.archiveRootDir] to strip
     * the root prefix, and the contents are placed in [LocalModelDescriptor.installDirName].
     * Readiness is verified by checking for known marker files inside the directory.
     */
    ZIP,

    /**
     * Model is a single binary file (e.g. a `.bin` ggml weight file).
     *
     * The file is downloaded directly and placed at
     * `filesDir/[LocalModelDescriptor.installDirName]/[LocalModelDescriptor.singleFileName]`.
     * No extraction step is performed. Readiness is verified by checking the file exists.
     */
    SINGLE_FILE,
}
