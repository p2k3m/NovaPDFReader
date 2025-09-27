package com.novapdf.reader

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.shockwave.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    @Config(application = TestPdfApp::class)
    fun `performSearch returns detected regions from rendered bitmap`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        whenever(pdfRepository.session).thenReturn(MutableStateFlow(session))
        whenever(pdfRepository.getPageSize(0)).thenReturn(Size(200, 280))

        val bitmap = Bitmap.createBitmap(200, 280, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        canvas.drawRect(20f, 40f, 180f, 100f, paint)
        canvas.drawRect(30f, 140f, 170f, 200f, paint)

        whenever(pdfRepository.renderPage(eq(0), eq(720))).thenReturn(bitmap)

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
        assertTrue(match.boundingBoxes.size >= 2)
        assertTrue(bitmap.isRecycled)
        verify(pdfRepository).renderPage(eq(0), eq(720))
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `performSearch returns empty list when rendering fails`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 1,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        whenever(pdfRepository.session).thenReturn(MutableStateFlow(session))
        whenever(pdfRepository.getPageSize(0)).thenReturn(Size(200, 280))
        whenever(pdfRepository.renderPage(eq(0), eq(720))).thenReturn(null)

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler
        )

        val viewModel = PdfViewerViewModel(app)

        val matches = viewModel.performSearch(0, "hello")

        assertTrue(matches.isEmpty())
        verify(pdfRepository).renderPage(eq(0), eq(720))
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
                return ApplicationProvider.getApplicationContext()
            }
        }
    }
}
