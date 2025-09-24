package com.novapdf.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PdfViewerViewModelSearchTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Config(sdk = [34], application = TestPdfApp::class)
    fun `performSearch extracts text runs on Api 34`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mockk<AnnotationRepository>()
        val pdfRepository = mockk<PdfDocumentRepository>()
        val adaptiveFlowManager = mockk<AdaptiveFlowManager>()
        val bookmarkManager = mockk<BookmarkManager>()
        val maintenanceScheduler = mockk<DocumentMaintenanceScheduler>(relaxed = true)

        val readingSpeed = MutableStateFlow(30f)
        val swipeSensitivity = MutableStateFlow(1f)
        val preloadTargets = MutableStateFlow(emptyList<Int>())

        every { adaptiveFlowManager.readingSpeedPagesPerMinute } returns readingSpeed
        every { adaptiveFlowManager.swipeSensitivity } returns swipeSensitivity
        every { adaptiveFlowManager.preloadTargets } returns preloadTargets
        every { adaptiveFlowManager.start() } just runs
        every { adaptiveFlowManager.stop() } just runs
        every { adaptiveFlowManager.trackPageChange(any(), any()) } just runs

        every { annotationRepository.annotationsForDocument(any()) } returns emptyList()
        every { bookmarkManager.bookmarks(any()) } returns emptyList()

        every { pdfRepository.preloadTiles(any(), any(), any()) } just runs

        val renderer = mockk<PdfRenderer>()
        val page = mockk<PdfRenderer.Page>()
        every { page.width } returns 220
        every { page.height } returns 400
        every { page.close() } just runs

        val glyphRuns = listOf(
            FakeGlyphRun("Lorem", Rect(0, 0, 100, 40)),
            FakeGlyphRun("ipsum", Rect(100, 0, 220, 40))
        )
        val textLine = FakeTextLine("Lorem ipsum", Rect(0, 0, 220, 40), glyphRuns)

        every { page.getText() } returns listOf(textLine)
        every { renderer.openPage(0) } returns page

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            renderer = renderer,
            fileDescriptor = mockk<ParcelFileDescriptor>()
        )
        val sessionFlow = MutableStateFlow<PdfDocumentSession?>(session)
        every { pdfRepository.session } returns sessionFlow

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler
        )

        val viewModel = PdfViewerViewModel(app)

        val matches = viewModel.performSearch(0, "lorem ipsum")

        assertEquals(1, matches.size)
        val match = matches.first()
        assertEquals(0, match.indexInPage)
        assertEquals(2, match.boundingBoxes.size)
        val first = match.boundingBoxes[0]
        val second = match.boundingBoxes[1]
        assertTrue(first.left <= 0.01f)
        assertTrue(first.right > 0.45f)
        assertTrue(second.left > 0.45f)
        assertTrue(second.right > 0.9f)

        verify(exactly = 1) { page.getText() }
        verify(exactly = 1) { page.close() }
    }

    @Test
    @Config(sdk = [33], application = TestPdfApp::class)
    fun `performSearch falls back to bitmap detection below Api 34`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mockk<AnnotationRepository>()
        val pdfRepository = mockk<PdfDocumentRepository>()
        val adaptiveFlowManager = mockk<AdaptiveFlowManager>()
        val bookmarkManager = mockk<BookmarkManager>()
        val maintenanceScheduler = mockk<DocumentMaintenanceScheduler>(relaxed = true)

        val readingSpeed = MutableStateFlow(30f)
        val swipeSensitivity = MutableStateFlow(1f)
        val preloadTargets = MutableStateFlow(emptyList<Int>())

        every { adaptiveFlowManager.readingSpeedPagesPerMinute } returns readingSpeed
        every { adaptiveFlowManager.swipeSensitivity } returns swipeSensitivity
        every { adaptiveFlowManager.preloadTargets } returns preloadTargets
        every { adaptiveFlowManager.start() } just runs
        every { adaptiveFlowManager.stop() } just runs
        every { adaptiveFlowManager.trackPageChange(any(), any()) } just runs

        every { annotationRepository.annotationsForDocument(any()) } returns emptyList()
        every { bookmarkManager.bookmarks(any()) } returns emptyList()

        every { pdfRepository.preloadTiles(any(), any(), any()) } just runs

        val renderer = mockk<PdfRenderer>()
        val page = mockk<PdfRenderer.Page>()
        every { page.width } returns 200
        every { page.height } returns 200
        every { page.close() } just runs

        every { page.render(any(), any(), any(), any()) } answers {
            val bitmap = arg<Bitmap>(0)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(Rect(10, 20, 90, 60), paint)
            canvas.drawRect(Rect(110, 120, 190, 160), paint)
        }
        every { renderer.openPage(0) } returns page

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            renderer = renderer,
            fileDescriptor = mockk<ParcelFileDescriptor>()
        )
        val sessionFlow = MutableStateFlow<PdfDocumentSession?>(session)
        every { pdfRepository.session } returns sessionFlow

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler
        )

        val viewModel = PdfViewerViewModel(app)

        val matches = viewModel.performSearch(0, "hello world")

        assertEquals(1, matches.size)
        val match = matches.first()
        assertEquals(0, match.indexInPage)
        assertEquals(2, match.boundingBoxes.size)
        val first = match.boundingBoxes[0]
        val second = match.boundingBoxes[1]
        assertTrue(first.top < second.top)
        assertTrue(first.left < first.right)
        assertTrue(second.left < second.right)

        verify(exactly = 1) { page.render(any(), any(), any(), any()) }
        verify(exactly = 1) { page.close() }
    }

    class TestPdfApp : NovaPdfApp() {
        override fun onCreate() {
            // Skip default initialisation to allow tests to inject fakes.
        }

        fun installDependencies(
            annotationRepository: AnnotationRepository,
            pdfRepository: PdfDocumentRepository,
            adaptiveFlowManager: AdaptiveFlowManager,
            bookmarkManager: BookmarkManager,
            documentMaintenanceScheduler: DocumentMaintenanceScheduler
        ) {
            setField("annotationRepository", annotationRepository)
            setField("pdfDocumentRepository", pdfRepository)
            setField("adaptiveFlowManager", adaptiveFlowManager)
            setField("bookmarkManager", bookmarkManager)
            setField("documentMaintenanceScheduler", documentMaintenanceScheduler)
        }

        private fun setField(name: String, value: Any) {
            val field = NovaPdfApp::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, value)
        }

        companion object {
            fun getInstance(): TestPdfApp {
                return androidx.test.core.app.ApplicationProvider.getApplicationContext()
            }
        }
    }

    private class FakeGlyphRun(
        private val text: String,
        private val bounds: Rect
    ) {
        fun getText(): String = text
        fun getBounds(): Rect = bounds
    }

    private class FakeTextLine(
        private val text: String,
        private val bounds: Rect,
        private val glyphRuns: List<Any>
    ) {
        fun getText(): String = text
        fun getBounds(): Rect = bounds
        fun getGlyphRuns(): List<Any> = glyphRuns
    }
}

