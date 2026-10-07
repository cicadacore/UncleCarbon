package com.hamoon.unclecarbon.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Regression tests for the legacy evidence migration in [StorageLayout].
 *
 * Older versions wrote captured evidence directly into the root of files/,
 * which forced the FileProvider to expose that whole directory. The migration
 * moves ONLY allowlisted evidence filenames into files/evidence/ and must
 * never touch any other private file. Pure JVM logic on a temporary directory.
 */
class StorageLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private val evidenceDir get() = File(filesDir, StorageLayout.EVIDENCE_DIR_NAME)

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
    }

    private fun write(dir: File, name: String, content: String = name): File {
        dir.mkdirs()
        return File(dir, name).also { it.writeText(content) }
    }

    @Test fun allowlist_acceptsKnownEvidenceNames() {
        assertTrue(StorageLayout.isLegacyEvidenceFileName("IMG_2026-10-04-12-30-45-123.jpg"))
        assertTrue(StorageLayout.isLegacyEvidenceFileName("VID_2026-10-04-12-30-45-123.mp4"))
        assertTrue(StorageLayout.isLegacyEvidenceFileName("AUD_2026-10-04-12-30-45-123.m4a"))
        assertTrue(StorageLayout.isLegacyEvidenceFileName("AUD_2024-01-01-00-00-00-000.mp3"))
        assertTrue(StorageLayout.isLegacyEvidenceFileName("sc_1727000000000.png"))
    }

    @Test fun allowlist_rejectsEverythingElse() {
        listOf(
            "IMG_notes.txt",
            "IMG_2026-10-04-12-30-45-123.jpg.bak",
            "IMG_2026-10-04-12-30-45-123.png",
            "VID_2026-10-04-12-30-45-123.jpg",
            "AUD_2026-10-04-12-30-45-123.wav",
            "AUD_x.m4a",
            "sc_abc.png",
            "sc_.png",
            "img_2026-10-04-12-30-45-123.jpg",
            "RAW_20240812_0042.dng",
            "unclecarbon_diagnostic_20261004_123045.txt",
            "rpmb_state.bin",
            "datastore",
            "profileInstalled",
            "../IMG_2026-10-04-12-30-45-123.jpg",
            ""
        ).forEach { name ->
            assertFalse("should not match: '$name'", StorageLayout.isLegacyEvidenceFileName(name))
        }
    }

    @Test fun migration_movesOnlyAllowlistedRootFiles() {
        val evidenceNames = listOf(
            "IMG_2026-10-04-12-30-45-123.jpg",
            "VID_2026-10-04-12-30-45-123.mp4",
            "AUD_2026-10-04-12-30-45-123.m4a",
            "sc_1727000000000.png"
        )
        val privateNames = listOf(
            "rpmb_state.bin",
            "unclecarbon_diagnostic_20261004_123045.txt",
            "IMG_notes.txt",
            "keys.json"
        )
        evidenceNames.forEach { write(filesDir, it) }
        privateNames.forEach { write(filesDir, it) }

        val result = StorageLayout.migrateLegacyEvidence(filesDir)

        assertEquals(StorageLayout.MigrationResult(moved = 4), result)
        evidenceNames.forEach { name ->
            assertFalse("left in root: $name", File(filesDir, name).exists())
            assertEquals(name, File(evidenceDir, name).readText())
        }
        privateNames.forEach { name ->
            assertTrue("private file moved: $name", File(filesDir, name).exists())
            assertFalse("private file copied: $name", File(evidenceDir, name).exists())
        }
    }

    @Test fun migration_doesNotDescendIntoSubdirectories() {
        val nested = write(File(filesDir, "Camera"), "IMG_2026-10-04-12-30-45-123.jpg")
        val prefsLike = write(File(filesDir, "datastore"), "VID_2026-10-04-12-30-45-123.mp4")

        assertEquals(StorageLayout.MigrationResult(), StorageLayout.migrateLegacyEvidence(filesDir))
        assertTrue(nested.exists())
        assertTrue(prefsLike.exists())
    }

    @Test fun migration_ignoresDirectoriesWithEvidenceNames() {
        val dir = File(filesDir, "IMG_2026-10-04-12-30-45-123.jpg").apply { mkdirs() }

        StorageLayout.migrateLegacyEvidence(filesDir)

        assertTrue(dir.isDirectory)
        assertFalse(File(evidenceDir, dir.name).exists())
    }

    @Test fun migration_neverOverwritesExistingDestination() {
        val name = "IMG_2026-10-04-12-30-45-123.jpg"
        write(filesDir, name, "legacy copy")
        write(evidenceDir, name, "already migrated")

        val result = StorageLayout.migrateLegacyEvidence(filesDir)

        assertEquals(StorageLayout.MigrationResult(skippedExisting = 1), result)
        assertEquals("legacy copy", File(filesDir, name).readText())
        assertEquals("already migrated", File(evidenceDir, name).readText())
    }

    @Test fun migration_isIdempotent() {
        write(filesDir, "AUD_2026-10-04-12-30-45-123.m4a", "audio")

        assertEquals(1, StorageLayout.migrateLegacyEvidence(filesDir).moved)
        assertEquals(StorageLayout.MigrationResult(), StorageLayout.migrateLegacyEvidence(filesDir))
        assertEquals("audio", File(evidenceDir, "AUD_2026-10-04-12-30-45-123.m4a").readText())
    }

    @Test fun migration_withNothingToMove_doesNotCreateEvidenceDir() {
        write(filesDir, "rpmb_state.bin")

        assertEquals(StorageLayout.MigrationResult(), StorageLayout.migrateLegacyEvidence(filesDir))
        assertFalse(evidenceDir.exists())
    }

    @Test fun migration_whenEvidenceDirCannotBeCreated_leavesFilesInPlace() {
        val name = "IMG_2026-10-04-12-30-45-123.jpg"
        write(filesDir, name)
        // A regular file squatting on the directory name blocks mkdirs().
        write(filesDir, StorageLayout.EVIDENCE_DIR_NAME, "not a directory")

        val result = StorageLayout.migrateLegacyEvidence(filesDir)

        assertEquals(StorageLayout.MigrationResult(failed = 1), result)
        assertTrue(File(filesDir, name).exists())
    }

    @Test fun migration_doesNotMoveSymlinks() {
        val target = write(tmp.newFolder("elsewhere"), "secret.bin")
        val link = File(filesDir, "IMG_2026-10-04-12-30-45-123.jpg")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeNoException("symlinks unsupported on this host", e)
        }

        StorageLayout.migrateLegacyEvidence(filesDir)

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(Files.exists(File(evidenceDir, link.name).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }
}
