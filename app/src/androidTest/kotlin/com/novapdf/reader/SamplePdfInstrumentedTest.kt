package com.novapdf.reader

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novapdf.reader.data.PdfDocumentRepository
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamplePdfInstrumentedTest {

    @Test
    fun openSampleDocumentAndCaptureScreenshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<NovaPdfApp>()
        val repository = PdfDocumentRepository(context)
        try {
            val sampleUri = SampleDocument.installIntoCache(context)
            val session = repository.open(sampleUri)
            assertNotNull("Sample PDF should open successfully", session)
            val nonNullSession = requireNotNull(session)
            assertEquals(1, nonNullSession.pageCount)

            val pageSize = repository.getPageSize(0)
            assertNotNull("Sample PDF should report a page size", pageSize)
            val fullPageSize = requireNotNull(pageSize)

            val fullPage = Rect(0, 0, fullPageSize.width, fullPageSize.height)
            val bitmap = repository.renderTile(0, fullPage, scale = 1f)
            assertNotNull("Sample PDF should render a bitmap", bitmap)
            val renderedPage = requireNotNull(bitmap)

            val screenshotDir = File(context.cacheDir, "instrumentation-screenshots").apply { mkdirs() }
            val screenshotFile = File(screenshotDir, "sample_page.png")
            FileOutputStream(screenshotFile).use { stream ->
                renderedPage.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            renderedPage.recycle()
            Log.d("SamplePdfInstrumentedTest", "Saved sample screenshot to ${screenshotFile.absolutePath}")
        } finally {
            repository.dispose()
        }
    }
}
