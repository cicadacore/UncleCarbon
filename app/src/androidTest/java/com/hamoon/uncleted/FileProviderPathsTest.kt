package com.hamoon.uncleted

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hamoon.uncleted.util.StorageLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression tests for the FileProvider path configuration (res/xml/file_paths.xml).
 *
 * Only files under the dedicated files/evidence/ and files/diagnostics/
 * directories may obtain a content URI. Anything else in files/, the cache
 * directory, or other app-private storage must be refused by
 * [FileProvider.getUriForFile] with an IllegalArgumentException rather than
 * being silently exposed.
 */
@RunWith(AndroidJUnit4::class)
class FileProviderPathsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val authority = "${context.packageName}.fileprovider"
    private val createdFiles = mutableListOf<File>()
    private val createdDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        createdFiles.forEach { it.delete() }
        createdDirs.forEach { it.delete() }
    }

    private fun probeFile(dir: File, name: String = "fileprovider_test_probe.txt"): File {
        if (!dir.isDirectory && dir.mkdirs()) createdDirs += dir
        return File(dir, name).also {
            it.writeText("fileprovider path test")
            createdFiles += it
        }
    }

    private fun uriFor(file: File) = FileProvider.getUriForFile(context, authority, file)

    // --- Approved directories ---

    @Test
    fun evidenceFile_obtainsContentUri() {
        val uri = uriFor(probeFile(StorageLayout.evidenceDir(context)))
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
    }

    @Test
    fun diagnosticsFile_obtainsContentUri() {
        val uri = uriFor(probeFile(StorageLayout.diagnosticsDir(context)))
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
    }

    // --- Everything else is refused ---

    @Test(expected = IllegalArgumentException::class)
    fun fileDirectlyUnderFilesDir_isRefused() {
        uriFor(probeFile(context.filesDir))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cacheDirFile_isRefused() {
        uriFor(probeFile(context.cacheDir))
    }

    @Test(expected = IllegalArgumentException::class)
    fun legacyCameraDirFile_isRefused() {
        uriFor(probeFile(StorageLayout.legacyCameraDir(context)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sharedPreferencesFile_isRefused() {
        uriFor(File(context.dataDir, "shared_prefs/fileprovider_test_probe.xml"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun siblingDirectorySharingEvidencePrefix_isRefused() {
        uriFor(probeFile(File(context.filesDir, "evidence_fileprovider_test")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalOutOfEvidenceDir_isRefused() {
        uriFor(File(StorageLayout.evidenceDir(context), "../fileprovider_test_probe.txt"))
    }
}
