package com.novapdf.reader.data

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.cache.DefaultCacheDirectories
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.util.Base64
import kotlin.text.Charsets
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

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

        val requireCache = PdfDocumentRepository::class.java.getDeclaredMethod("requireBitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = requireCache.invoke(repository)
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

        val requireCache = PdfDocumentRepository::class.java.getDeclaredMethod("requireBitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = requireCache.invoke(repository)
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

    @Test
    fun harnessFixtureTriggersPreemptiveRepair() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val constants = Class.forName("com.novapdf.reader.data.PdfDocumentRepositoryKt")
        val markerField = constants.getDeclaredField("HARNESS_FIXTURE_MARKER").apply {
            isAccessible = true
        }
        val harnessMarker = markerField.get(null) as String
        val minCountField = constants.getDeclaredField("PRE_REPAIR_MIN_PAGE_COUNT").apply {
            isAccessible = true
        }
        val minPageCount = minCountField.getInt(null)

        val harnessFile = File(context.cacheDir, "harness-fixture.pdf")
        val pdfSkeleton = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Count $minPageCount /Kids [3 0 R] >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 0 >>
            stream
            endstream
            endobj
            % $harnessMarker
            %%EOF
            """.trimIndent()
        harnessFile.writeText(pdfSkeleton, Charsets.ISO_8859_1)

        val detectOversized = PdfDocumentRepository::class.declaredFunctions
            .first { it.name == "detectOversizedPageTree" }
            .apply { isAccessible = true }

        val shouldRepair = detectOversized.callSuspend(
            repository,
            harnessFile.toUri(),
            harnessFile.length(),
            null,
        ) as Boolean

        assertTrue(
            "Harness fixture should trigger pre-emptive repair",
            shouldRepair
        )

        repository.dispose()
        harnessFile.delete()
    }

    @Test
    fun harnessFixtureWithoutLargeCountStillRepairs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val constants = Class.forName("com.novapdf.reader.data.PdfDocumentRepositoryKt")
        val markerField = constants.getDeclaredField("HARNESS_FIXTURE_MARKER").apply {
            isAccessible = true
        }
        val harnessMarker = markerField.get(null) as String

        val harnessFile = File(context.cacheDir, "harness-fixture-minimal.pdf")
        val pdfSkeleton = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Count 4 /Kids [3 0 R] >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length 0 >>
            stream
            endstream
            endobj
            % $harnessMarker
            %%EOF
            """.trimIndent()
        harnessFile.writeText(pdfSkeleton, Charsets.ISO_8859_1)

        val detectOversized = PdfDocumentRepository::class.declaredFunctions
            .first { it.name == "detectOversizedPageTree" }
            .apply { isAccessible = true }

        val shouldRepair = detectOversized.callSuspend(
            repository,
            harnessFile.toUri(),
            harnessFile.length(),
            null,
        ) as Boolean

        assertTrue(
            "Harness fixtures should always be repaired to stabilise Pdfium",
            shouldRepair
        )

        repository.dispose()
        harnessFile.delete()
    }

    @Test
    fun pageTreeInspectionHandlesLateCountNodes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )

        val constants = Class.forName("com.novapdf.reader.data.PdfDocumentRepositoryKt")
        val minCountField = constants.getDeclaredField("PRE_REPAIR_MIN_PAGE_COUNT").apply {
            isAccessible = true
        }
        val minPageCount = minCountField.getInt(null)

        val fillerBytes = 12 * 1024 * 1024
        val pdfBytes = ByteArrayOutputStream().use { output ->
            OutputStreamWriter(output, Charsets.ISO_8859_1).use { writer ->
                writer.write("%PDF-1.4\n")
                writer.flush()
                output.write(ByteArray(fillerBytes) { 'X'.code.toByte() })
                writer.write(
                    """
                    1 0 obj
                    << /Type /Catalog /Pages 2 0 R >>
                    endobj
                    2 0 obj
                    << /Type /Pages /Count $minPageCount /Kids [3 0 R] >>
                    endobj
                    3 0 obj
                    << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>
                    endobj
                    4 0 obj
                    << /Length 0 >>
                    stream
                    endstream
                    endobj
                    %%EOF
                    """.trimIndent()
                )
            }
            output.toByteArray()
        }

        val delayedFile = File(context.cacheDir, "late-page-tree.pdf")
        delayedFile.writeBytes(pdfBytes)

        val detectOversized = PdfDocumentRepository::class.declaredFunctions
            .first { it.name == "detectOversizedPageTree" }
            .apply { isAccessible = true }

        val shouldRepair = detectOversized.callSuspend(
            repository,
            delayedFile.toUri(),
            delayedFile.length(),
            null,
        ) as Boolean

        assertTrue(
            "Large documents should trigger pre-emptive repair even when the page tree appears late",
            shouldRepair,
        )

        repository.dispose()
        delayedFile.delete()
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

        val requireCache = PdfDocumentRepository::class.java.getDeclaredMethod("requireBitmapCache").apply {
            isAccessible = true
        }
        val bitmapCache = requireCache.invoke(repository)

        assertEquals("LruBitmapCache", bitmapCache.javaClass.simpleName)

        repository.dispose()
    }
}
