package com.novapdf.reader

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.shockwave.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun `search delegates to Lucene coordinator`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 3,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        whenever(pdfRepository.session).thenReturn(MutableStateFlow(session))

        val searchResults = listOf(
            SearchResult(
                pageIndex = 1,
                matches = listOf(SearchMatch(0, listOf(RectSnapshot(0f, 0f, 1f, 1f))))
            )
        )
        whenever(searchCoordinator.search(eq(session), eq("galaxy"))).thenReturn(searchResults)

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.search("galaxy")
        advanceUntilIdle()

        verify(searchCoordinator).search(eq(session), eq("galaxy"))
        assertEquals(searchResults, viewModel.uiState.value.searchResults)
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `blank query clears previous results`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 2,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        whenever(pdfRepository.session).thenReturn(MutableStateFlow(session))

        whenever(searchCoordinator.search(eq(session), any())).thenReturn(
            listOf(
                SearchResult(
                    pageIndex = 0,
                    matches = listOf(SearchMatch(0, listOf(RectSnapshot(0f, 0f, 1f, 1f))))
                )
            )
        )

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.search("history")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.searchResults.isNotEmpty())

        viewModel.search("")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openDocument warms up index`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))

        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 5,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        whenever(pdfRepository.open(any())).thenReturn(session)
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(session))
        whenever(pdfRepository.renderPage(eq(0), any())).thenReturn(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator
        )

        val viewModel = PdfViewerViewModel(app)

        val uri = Uri.parse("file://doc")
        viewModel.openDocument(uri)
        advanceUntilIdle()

        verify(searchCoordinator).prepare(eq(session))
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
            documentMaintenanceScheduler: DocumentMaintenanceScheduler,
            searchCoordinator: LuceneSearchCoordinator
        ) {
            setField("annotationRepository", annotationRepository)
            setField("pdfDocumentRepository", pdfRepository)
            setField("adaptiveFlowManager", adaptiveFlowManager)
            setField("bookmarkManager", bookmarkManager)
            setField("documentMaintenanceScheduler", documentMaintenanceScheduler)
            setField("searchCoordinator", searchCoordinator)
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
