package com.hamoon.unclecarbon.util

import android.content.Context
import com.hamoon.unclecarbon.data.SecurityPreferences
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * On-disk layout for app-private files, kept in sync with the FileProvider
 * configuration in `res/xml/file_paths.xml`:
 *
 * ```
 * files/
 *     evidence/     captured photos, videos and audio   (shared via FileProvider)
 *     diagnostics/  diagnostic reports                  (shared via FileProvider)
 *     Camera/       legacy vault-era camera folder      (listed/purged, NOT shared)
 * ```
 *
 * Only the dedicated `evidence/` and `diagnostics/` directories are exposed
 * through the FileProvider; the rest of `files/` (preferences, keys,
 * other private state, ...) and the cache directory are not.
 */
object StorageLayout {

    private const val TAG = "StorageLayout"

    const val EVIDENCE_DIR_NAME = "evidence"
    const val DIAGNOSTICS_DIR_NAME = "diagnostics"
    const val LEGACY_CAMERA_DIR_NAME = "Camera"

    /** `yyyy-MM-dd-HH-mm-ss-SSS`, the capture filename timestamp used since v1. */
    private const val CAPTURE_TIMESTAMP = "[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{2}-[0-9]{3}"

    /**
     * Strict allowlist of evidence filenames that earlier versions wrote
     * directly into the root of `files/`. Anything else there is left alone.
     */
    private val LEGACY_EVIDENCE_NAME_PATTERNS = listOf(
        Regex("^IMG_$CAPTURE_TIMESTAMP\\.jpg$"),        // CameraHandler photos
        Regex("^VID_$CAPTURE_TIMESTAMP\\.mp4$"),        // CameraHandler videos
        Regex("^AUD_$CAPTURE_TIMESTAMP\\.(m4a|mp3)$"),  // AudioRecorder (.mp3 before v10)
        Regex("^sc_[0-9]{1,19}\\.png$")                 // removed screenshot capture
    )

    data class MigrationResult(
        val moved: Int = 0,
        val skippedExisting: Int = 0,
        val failed: Int = 0
    )

    /** `files/evidence/`, created if needed. */
    fun evidenceDir(context: Context): File = ensureDir(File(context.filesDir, EVIDENCE_DIR_NAME))

    /** `files/diagnostics/`, created if needed. */
    fun diagnosticsDir(context: Context): File {
        check(SecurityPreferences.isUserUnlocked(context)) { "Unlock required for diagnostics" }
        val ce = SecurityPreferences.requireCredentialStorageContext(context)
        return ensureDir(File(ce.filesDir, DIAGNOSTICS_DIR_NAME))
    }

    /**
     * Diagnostic reports belong only in credential-encrypted storage. Delete any
     * report found in device-protected storage (older versions wrote there);
     * never import them.
     */
    fun purgeLegacyDeviceDiagnostics(context: Context) {
        try {
            val root = context.createDeviceProtectedStorageContext().filesDir
            val names = Regex("unclecarbon_(bugreport|diagnostic)_[0-9]{8}_[0-9]{6}\\.txt")
            for (dir in listOf(root, File(root, DIAGNOSTICS_DIR_NAME))) {
                if (Files.isSymbolicLink(dir.toPath())) continue
                dir.listFiles()?.filter { names.matches(it.name) }?.forEach {
                    if (Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)) it.delete()
                }
            }
        } catch (_: Exception) { }
    }

    /** Legacy `files/Camera/`; never created, may not exist. */
    fun legacyCameraDir(context: Context): File = File(context.filesDir, LEGACY_CAMERA_DIR_NAME)

    /**
     * Moves recognized legacy evidence from the root of `files/` into
     * `files/evidence/`. Safe to call repeatedly; see [migrateLegacyEvidence].
     * Must not be called before the user has unlocked (CE storage).
     */
    fun migrateLegacyEvidence(context: Context): MigrationResult {
        val result = migrateLegacyEvidence(context.filesDir)
        if (result != MigrationResult()) {
            Log.i(TAG, "Legacy evidence migration: moved=${result.moved}, " +
                "skippedExisting=${result.skippedExisting}, failed=${result.failed}")
        }
        return result
    }

    /** True only for filenames on the strict legacy evidence allowlist. */
    fun isLegacyEvidenceFileName(name: String): Boolean =
        LEGACY_EVIDENCE_NAME_PATTERNS.any { it.matches(name) }

    /**
     * Framework-free migration core, operating on the given `files/` root.
     *
     * - Only top-level entries whose names are on the allowlist and that are
     *   plain regular files (not directories, not symlinks) are moved.
     * - An existing destination is never overwritten; that file is skipped
     *   and left in place.
     * - A failed move leaves the source untouched and continues.
     * - Idempotent: once moved, a file is no longer in the root to match.
     */
    @Synchronized
    fun migrateLegacyEvidence(filesDir: File): MigrationResult {
        val candidates = filesDir.listFiles { file -> isLegacyEvidenceFileName(file.name) }
        if (candidates.isNullOrEmpty()) return MigrationResult()

        val evidenceDir = File(filesDir, EVIDENCE_DIR_NAME)
        val evidencePath = evidenceDir.toPath()
        if (Files.isSymbolicLink(evidencePath) || !ensureDir(evidenceDir).isDirectory) {
            return MigrationResult(failed = candidates.size)
        }

        var moved = 0
        var skippedExisting = 0
        var failed = 0
        for (source in candidates) {
            val sourcePath = source.toPath()
            if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) continue

            val destPath = evidencePath.resolve(source.name)
            if (Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) {
                skippedExisting++
                continue
            }
            try {
                // No REPLACE_EXISTING: throws rather than overwrite a destination
                // that appeared after the check above.
                Files.move(sourcePath, destPath)
                moved++
            } catch (_: Exception) {
                failed++
            }
        }
        return MigrationResult(moved, skippedExisting, failed)
    }

    private fun ensureDir(dir: File): File {
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }
}
