package com.novapdf.reader.data

import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.cache.DefaultCacheDirectories
import com.novapdf.reader.model.PdfRenderProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfDocumentRepositoryThreadingTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun renderProgressUpdatesRequireWorkerThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )
        val latch = CountDownLatch(1)
        var captured: Throwable? = null
        Handler(Looper.getMainLooper()).post {
            try {
                repository.emitRenderProgressForTesting(PdfRenderProgress.Rendering(0, 0.5f))
            } catch (throwable: Throwable) {
                captured = throwable
            } finally {
                latch.countDown()
            }
        }
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(captured is IllegalStateException)
        repository.dispose()
    }

    @Test
    fun renderProgressUpdatesPropagateOffMainThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(
            context,
            dispatcher,
            cacheDirectories = DefaultCacheDirectories(context),
        )
        val backgroundLatch = CountDownLatch(1)
        Thread {
            repository.emitRenderProgressForTesting(PdfRenderProgress.Rendering(3, 0.25f))
            repository.emitRenderProgressForTesting(PdfRenderProgress.Idle)
            backgroundLatch.countDown()
        }.start()
        assertTrue(backgroundLatch.await(2, TimeUnit.SECONDS))
        assertEquals(PdfRenderProgress.Idle, repository.renderProgress.value)
        repository.dispose()
    }

    @Test
    fun bitmapCacheOperationsAreThreadSafe() = runTest {
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
        val getMethod = bitmapCache.javaClass.getDeclaredMethod("getBitmap", String::class.java).apply {
            isAccessible = true
        }

        val startLatch = CountDownLatch(4)
        val goLatch = CountDownLatch(1)
        val finishedLatch = CountDownLatch(4)
        val failure = AtomicReference<Throwable?>()

        repeat(4) { index ->
            Thread {
                try {
                    startLatch.countDown()
                    goLatch.await(2, TimeUnit.SECONDS)
                    repeat(64) { iteration ->
                        val key = "thread-$index-$iteration"
                        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                        putMethod.invoke(bitmapCache, key, bitmap)
                        val cached = getMethod.invoke(bitmapCache, key) as? Bitmap
                        if (cached == null) {
                            failure.compareAndSet(null, AssertionError("Missing cached bitmap for $key"))
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                } finally {
                    finishedLatch.countDown()
                }
            }.start()
        }

        assertTrue("Worker threads failed to start", startLatch.await(2, TimeUnit.SECONDS))
        goLatch.countDown()
        assertTrue("Worker threads timed out", finishedLatch.await(5, TimeUnit.SECONDS))

        failure.get()?.let { throw it }

        repository.dispose()
    }
}
