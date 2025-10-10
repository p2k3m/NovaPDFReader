package com.novapdf.reader.data

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.logging.CrashReporter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BitmapPoolBehaviorTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun reusesBitmapForSmallerDimensions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val crashReporter = RecordingCrashReporter()
        val repository = PdfDocumentRepository(context, dispatcher, crashReporter)
        try {
            val obtainBitmap = PdfDocumentRepository::class.java.getDeclaredMethod(
                "obtainBitmap",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Bitmap.Config::class.java,
            ).apply { isAccessible = true }
            val recycleBitmap = PdfDocumentRepository::class.java.getDeclaredMethod(
                "recycleBitmap",
                Bitmap::class.java,
            ).apply { isAccessible = true }

            val original = obtainBitmap.invoke(repository, 128, 128, Bitmap.Config.ARGB_8888) as Bitmap
            recycleBitmap.invoke(repository, original)

            val reused = obtainBitmap.invoke(repository, 64, 64, Bitmap.Config.ARGB_8888) as Bitmap
            assertSame(original, reused)
            assertEquals(64, reused.width)
            assertEquals(64, reused.height)

            recycleBitmap.invoke(repository, reused)
        } finally {
            repository.dispose()
        }
    }

    @Test
    fun reportsBitmapPoolMetrics() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val crashReporter = RecordingCrashReporter()
        val repository = PdfDocumentRepository(context, dispatcher, crashReporter)
        try {
            val obtainBitmap = PdfDocumentRepository::class.java.getDeclaredMethod(
                "obtainBitmap",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Bitmap.Config::class.java,
            ).apply { isAccessible = true }
            val recycleBitmap = PdfDocumentRepository::class.java.getDeclaredMethod(
                "recycleBitmap",
                Bitmap::class.java,
            ).apply { isAccessible = true }
            val reportMetrics = PdfDocumentRepository::class.java.getDeclaredMethod(
                "reportBitmapPoolMetricsForTesting",
            ).apply { isAccessible = true }

            val first = obtainBitmap.invoke(repository, 32, 32, Bitmap.Config.ARGB_8888) as Bitmap
            recycleBitmap.invoke(repository, first)

            val second = obtainBitmap.invoke(repository, 32, 32, Bitmap.Config.ARGB_8888) as Bitmap
            recycleBitmap.invoke(repository, second)

            reportMetrics.invoke(repository)

            assertTrue(crashReporter.breadcrumbs.any { it.contains("bitmap_pool") && it.contains("hitRate=") })
        } finally {
            repository.dispose()
        }
    }

    private class RecordingCrashReporter : CrashReporter {
        val breadcrumbs = mutableListOf<String>()

        override fun install() = Unit

        override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) = Unit

        override fun logBreadcrumb(message: String) {
            breadcrumbs += message
        }
    }
}
