package com.novapdf.reader

import android.content.Context
import android.graphics.Bitmap
import com.novapdf.reader.logging.NovaLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novapdf.reader.CacheFileNames
import com.novapdf.reader.data.PdfDocumentRepository
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SamplePdfInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var sampleDocument: SampleDocument

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun openSampleDocumentAndCaptureScreenshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PdfDocumentRepository(context)
        try {
            val sampleUri = sampleDocument.installIntoCache(context)
            val session = repository.open(sampleUri)
            assertNotNull("Sample PDF should open successfully", session)
            val nonNullSession = requireNotNull(session)
            assertEquals(1, nonNullSession.pageCount)

            val pageSize = repository.getPageSize(0)
            assertNotNull("Sample PDF should report a page size", pageSize)
            val fullPageSize = requireNotNull(pageSize)

            val targetWidth = fullPageSize.width.coerceAtLeast(1)
            val bitmap = repository.renderPage(0, targetWidth)
            assertNotNull("Sample PDF should render a bitmap", bitmap)
            val renderedPage = requireNotNull(bitmap)

            val screenshotDir = File(context.cacheDir, CacheFileNames.INSTRUMENTATION_SCREENSHOT_DIRECTORY)
                .apply { mkdirs() }
            val screenshotFile = File(screenshotDir, CacheFileNames.SAMPLE_SCREENSHOT_FILE)
            FileOutputStream(screenshotFile).use { stream ->
                renderedPage.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            renderedPage.recycle()
            NovaLog.d("SamplePdfInstrumentedTest", "Saved sample screenshot to ${screenshotFile.absolutePath}")
        } finally {
            repository.dispose()
        }
    }
}
