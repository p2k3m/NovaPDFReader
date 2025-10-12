package com.novapdf.reader

import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.cache.DefaultCacheDirectories
import com.novapdf.reader.data.PdfDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class ThousandPagePdfPdfiumTest {

    @Test
    fun `pdfium can open generated thousand page document`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TestPdfApp>()
        val cacheFile = File(app.cacheDir, "pdfium-thousand-pages.pdf")
        cacheFile.parentFile?.mkdirs()
        cacheFile.outputStream().use { output ->
            ThousandPagePdfWriter(1_000).writeTo(output)
        }

        val repository = PdfDocumentRepository(
            app,
            cacheDirectories = DefaultCacheDirectories(app),
        )
        try {
            val session = withContext(Dispatchers.IO) {
                repository.open(cacheFile.toUri())
            }
            assertEquals(1_000, session.pageCount)
        } finally {
            repository.dispose()
        }
    }
}
