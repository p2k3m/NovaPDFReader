package com.novapdf.reader.data

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfDocumentRepositoryThreadingTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun renderProgressUpdatesRequireWorkerThread() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = PdfDocumentRepository(context, dispatcher)
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
        val repository = PdfDocumentRepository(context, dispatcher)
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
}
