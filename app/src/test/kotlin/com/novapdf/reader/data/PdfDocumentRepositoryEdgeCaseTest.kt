package com.novapdf.reader.data

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfDocumentRepositoryEdgeCaseTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun rejectsOversizedPdfDocuments() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(context, dispatcher)
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

    @Test
    fun clearsBitmapCacheWhenStorageLow() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(context, dispatcher)

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
}
