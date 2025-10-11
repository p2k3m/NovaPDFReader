package com.novapdf.reader.data

import android.app.Application
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.TestPdfApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class PdfDocumentRepositoryTileMathTest {

    private lateinit var repository: PdfDocumentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        repository = PdfDocumentRepository(context, StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        repository.dispose()
    }

    @Test
    fun `fractional tiles return empty rect when page size is zero`() {
        val method = PdfDocumentRepository::class.java.getDeclaredMethod(
            "toPageRect",
            RectF::class.java,
            Size::class.java,
        ).apply { isAccessible = true }

        val resultWithZeroWidth = method.invoke(repository, RectF(0f, 0f, 1f, 1f), Size(0, 100)) as Rect
        val resultWithZeroHeight = method.invoke(repository, RectF(0f, 0f, 1f, 1f), Size(100, 0)) as Rect

        assertTrue(resultWithZeroWidth.isEmpty)
        assertTrue(resultWithZeroHeight.isEmpty)
    }

    @Test
    fun `fractional tiles are converted to pixel aligned rects`() {
        val method = PdfDocumentRepository::class.java.getDeclaredMethod(
            "toPageRect",
            RectF::class.java,
            Size::class.java,
        ).apply { isAccessible = true }

        val input = RectF(0.15f, 0.35f, 0.85f, 0.95f)
        val size = Size(1000, 2000)

        val result = method.invoke(repository, input, size) as Rect

        assertEquals(150, result.left)
        assertEquals(700, result.top)
        assertEquals(850, result.right)
        assertEquals(1900, result.bottom)
    }
}
