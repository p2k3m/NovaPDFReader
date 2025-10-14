package com.novapdf.reader.cache

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultCacheDirectoriesInstrumentationTest {

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q, maxSdkVersion = Build.VERSION_CODES.Q)
    fun instantiateCacheDirectoriesOnApi29() {
        assertCacheDirectoriesInstantiate(Build.VERSION_CODES.Q)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S_V2, maxSdkVersion = Build.VERSION_CODES.S_V2)
    fun instantiateCacheDirectoriesOnApi32() {
        assertCacheDirectoriesInstantiate(Build.VERSION_CODES.S_V2)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, maxSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun instantiateCacheDirectoriesOnApi34() {
        assertCacheDirectoriesInstantiate(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    }

    private fun assertCacheDirectoriesInstantiate(expectedApi: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cacheDirectories = DefaultCacheDirectories(context)

        val root = cacheDirectories.root()
        assertEquals(expectedApi, Build.VERSION.SDK_INT)

        val expectedRoot = File(context.cacheDir, "pdf-cache")
        assertEquals(expectedRoot.canonicalFile, root.canonicalFile)

        cacheDirectories.ensureSubdirectories()

        val expectedSubdirectories = listOf("docs", "thumbs", "tiles", "indexes")
        expectedSubdirectories.forEach { name ->
            val directory = File(root, name)
            assertTrue("$name directory should exist", directory.exists())
            assertTrue("$name directory should be a directory", directory.isDirectory)
        }
    }
}
