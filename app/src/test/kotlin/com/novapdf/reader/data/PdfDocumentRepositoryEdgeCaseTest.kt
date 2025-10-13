package com.novapdf.reader.data

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.cache.DefaultCacheDirectories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfDocumentRepositoryEdgeCaseTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun rejectsOversizedPdfDocuments() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )
        val oversized = File(context.cacheDir, "oversized.pdf")
        val limit = 100L * 1024L * 1024L
        RandomAccessFile(oversized, "rw").use { raf ->
            raf.setLength(limit + 1L)
        }

        val result = repository.open(Uri.fromFile(oversized))

        assertNull(result)
        repository.dispose()
        oversized.delete()
    }

    @Suppress("DEPRECATION")
    @Test
    fun clearsBitmapCacheWhenStorageLow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val bitmapCacheField = PdfDocumentRepository::class.java.getDeclaredField("bitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = bitmapCacheField.get(repository)
        val putMethod = bitmapCache.javaClass.getDeclaredMethod("putBitmap", String::class.java, Bitmap::class.java).apply {
            isAccessible = true
        }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        putMethod.invoke(bitmapCache, "sample", bitmap)

        val callbacksField = PdfDocumentRepository::class.java.getDeclaredField("componentCallbacks").apply {
            isAccessible = true
        }
        val callbacks = callbacksField.get(repository) as ComponentCallbacks2
        @Suppress("DEPRECATION")
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        advanceUntilIdle()

        val getMethod = bitmapCache.javaClass.getDeclaredMethod("getBitmap", String::class.java).apply {
            isAccessible = true
        }
        val cached = getMethod.invoke(bitmapCache, "sample") as? Bitmap

        assertNull(cached)
        assertTrue(bitmap.isRecycled)

        repository.dispose()
    }

    @Suppress("DEPRECATION")
    @Test
    fun trimsBitmapCacheWhenMemoryModerate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val bitmapCacheField = PdfDocumentRepository::class.java.getDeclaredField("bitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = bitmapCacheField.get(repository)
        val putMethod = bitmapCache.javaClass.getDeclaredMethod("putBitmap", String::class.java, Bitmap::class.java).apply {
            isAccessible = true
        }
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        putMethod.invoke(bitmapCache, "sample", bitmap)

        val callbacksField = PdfDocumentRepository::class.java.getDeclaredField("componentCallbacks").apply {
            isAccessible = true
        }
        val callbacks = callbacksField.get(repository) as ComponentCallbacks2
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)

        advanceUntilIdle()

        val getMethod = bitmapCache.javaClass.getDeclaredMethod("getBitmap", String::class.java).apply {
            isAccessible = true
        }
        val cached = getMethod.invoke(bitmapCache, "sample") as? Bitmap

        assertNull(cached)
        assertTrue(bitmap.isRecycled)

        repository.dispose()
    }

    @Test
    @Config(sdk = [29])
    fun usesLruBitmapCacheOnApi29() = runTest {
        assertBitmapCacheIsLru(StandardTestDispatcher(testScheduler))
    }

    @Test
    @Config(sdk = [32])
    fun usesLruBitmapCacheOnApi32() = runTest {
        assertBitmapCacheIsLru(StandardTestDispatcher(testScheduler))
    }

    @Test
    @Config(sdk = [34])
    fun usesLruBitmapCacheOnApi34() = runTest {
        assertBitmapCacheIsLru(StandardTestDispatcher(testScheduler))
    }

    @Test
    fun caffeineDependencyIsRemoved() {
        val result = runCatching {
            Class.forName("com.github.benmanes.caffeine.cache.Caffeine")
        }

        assertTrue("Caffeine dependency should be absent", result.isFailure)
    }

    @Test
    fun encryptedMonsterPdfSurfacesAccessDenied() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )
        val monsterFile = File(context.cacheDir, "monster-encrypted.pdf")
        monsterFile.writeBytes(loadBase64Fixture("fixtures/monster-encrypted.base64"))

        try {
            try {
                repository.open(Uri.fromFile(monsterFile))
                fail("Expected open() to throw PdfOpenException for encrypted monster PDF")
            } catch (exception: PdfOpenException) {
                assertEquals(PdfOpenException.Reason.ACCESS_DENIED, exception.reason)
            }
        } finally {
            repository.dispose()
            monsterFile.delete()
        }
    }

    private fun loadBase64Fixture(path: String): ByteArray {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test fixture: $path"
        }
        val base64 = stream.bufferedReader().use { reader ->
            buildString {
                reader.forEachLine { line ->
                    append(line.trim())
                }
            }
        }
        return Base64.getDecoder().decode(base64)
    }
    private suspend fun assertBitmapCacheIsLru(dispatcher: TestDispatcher) {
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val bitmapCacheField = PdfDocumentRepository::class.java.getDeclaredField("bitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = bitmapCacheField.get(repository)

        assertEquals("LruBitmapCache", bitmapCache.javaClass.simpleName)

        repository.dispose()
    }
}
