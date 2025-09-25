package com.novapdf.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import com.novapdf.reader.search.TextRunSnapshot
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
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
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()

        val readingSpeed = MutableStateFlow(30f)
        val swipeSensitivity = MutableStateFlow(1f)
        val preloadTargets = MutableStateFlow(emptyList<Int>())

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(readingSpeed)
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(swipeSensitivity)
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(preloadTargets)

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val renderer = mock<PdfRenderer>()
        val page = mock<PdfRenderer.Page>()
        whenever(page.width).thenReturn(220)
        whenever(page.height).thenReturn(400)
        whenever(renderer.openPage(0)).thenReturn(page)

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            renderer = renderer,
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        val sessionFlow = MutableStateFlow<PdfDocumentSession?>(session)
        whenever(pdfRepository.session).thenReturn(sessionFlow)

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler
        )

        val viewModel = object : PdfViewerViewModel(app) {
            override fun extractTextRuns(page: PdfRenderer.Page): List<TextRunSnapshot> {
                return listOf(
                    TextRunSnapshot(
                        text = "Lorem ipsum",
                        bounds = listOf(
                            RectF(0f, 0f, 100f, 40f),
                            RectF(100f, 0f, 220f, 40f)
                        )
                    )
                )
            }
        }

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

        verify(page).close()
    }

    @Test
    @Config(sdk = [33], application = TestPdfApp::class)
    fun `performSearch falls back to bitmap detection below Api 34`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()

        val readingSpeed = MutableStateFlow(30f)
        val swipeSensitivity = MutableStateFlow(1f)
        val preloadTargets = MutableStateFlow(emptyList<Int>())

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(readingSpeed)
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(swipeSensitivity)
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(preloadTargets)

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val renderer = mock<PdfRenderer>()
        val page = mock<PdfRenderer.Page>()
        whenever(page.width).thenReturn(200)
        whenever(page.height).thenReturn(200)

        doAnswer {
            val bitmap = it.getArgument<Bitmap>(0)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            canvas.drawRect(Rect(10, 20, 90, 60), paint)
            canvas.drawRect(Rect(110, 120, 190, 160), paint)
            Unit
        }.whenever(page).render(
            any<Bitmap>(),
            anyOrNull<Rect>(),
            anyOrNull<Matrix>(),
            eq(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        )
        whenever(renderer.openPage(0)).thenReturn(page)

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            renderer = renderer,
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        val sessionFlow = MutableStateFlow<PdfDocumentSession?>(session)
        whenever(pdfRepository.session).thenReturn(sessionFlow)

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

        verify(page).render(
            any<Bitmap>(),
            anyOrNull<Rect>(),
            anyOrNull<Matrix>(),
            eq(PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        )
        verify(page).close()
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
}

