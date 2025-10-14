package com.novapdf.reader.cache

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.TestPdfApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    application = TestPdfApp::class,
    sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.S_V2, Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
)
class DefaultCacheDirectoriesApiCompatibilityTest {

    @Test
    fun `default cache directories instantiate on target api levels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val cacheDirectories = DefaultCacheDirectories(context)
        val root = cacheDirectories.root()

        val expectedRoot = File(context.cacheDir, "pdf-cache")
        assertEquals(expectedRoot.canonicalFile, root.canonicalFile)

        val documents = cacheDirectories.documents()
        assertEquals(File(root, "docs").canonicalFile, documents.canonicalFile)
        assertTrue(documents.exists())

        val thumbnails = cacheDirectories.thumbnails()
        assertEquals(File(root, "thumbs").canonicalFile, thumbnails.canonicalFile)
        assertTrue(thumbnails.exists())

        val tiles = cacheDirectories.tiles()
        assertEquals(File(root, "tiles").canonicalFile, tiles.canonicalFile)
        assertTrue(tiles.exists())

        val indexes = cacheDirectories.indexes()
        assertEquals(File(root, "indexes").canonicalFile, indexes.canonicalFile)
        assertTrue(indexes.exists())
    }
}
