package com.novapdf.reader

import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfOpenException
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.shockwave.pdfium.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.CancellationException
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
        val downloadManager = mock<PdfDownloadManager>()

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
            searchCoordinator,
            downloadManager
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
        val downloadManager = mock<PdfDownloadManager>()

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
            searchCoordinator,
            downloadManager
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
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))

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
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        val uri = Uri.parse("file://doc")
        viewModel.openDocument(uri)
        advanceUntilIdle()

        verify(searchCoordinator).prepare(eq(session))
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openRemoteDocument downloads then opens PDF`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))

        val session = PdfDocumentSession(
            documentId = "remote_doc",
            uri = Uri.parse("file://remote_doc"),
            pageCount = 2,
            document = mock<PdfDocument>(),
            fileDescriptor = mock<ParcelFileDescriptor>()
        )
        val downloadUri = Uri.parse("https://example.com/doc.pdf")
        whenever(downloadManager.download(eq(downloadUri.toString()))).thenReturn(Result.success(downloadUri))
        whenever(pdfRepository.open(eq(downloadUri))).thenReturn(session)
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(session))
        whenever(pdfRepository.renderPage(eq(0), any())).thenReturn(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.openRemoteDocument(downloadUri.toString())
        advanceUntilIdle()

        verify(downloadManager).download(eq(downloadUri.toString()))
        verify(pdfRepository).open(eq(downloadUri))
        verify(searchCoordinator).prepare(eq(session))
        assertEquals("remote_doc", viewModel.uiState.value.documentId)
        assertEquals(DocumentStatus.Idle, viewModel.uiState.value.documentStatus)
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openRemoteDocument surfaces download failure`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))

        val failingUrl = "https://example.com/bad.pdf"
        whenever(downloadManager.download(eq(failingUrl))).thenReturn(Result.failure(IllegalStateException("bad")))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.openRemoteDocument(failingUrl)
        advanceUntilIdle()

        verify(downloadManager).download(eq(failingUrl))
        val status = viewModel.uiState.value.documentStatus
        assertTrue(status is DocumentStatus.Error)
        assertEquals(app.getString(R.string.error_remote_open_failed), (status as DocumentStatus.Error).message)
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openRemoteDocument recovers from thrown download exception`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))

        val failingUrl = "https://example.com/bad.pdf"
        whenever(downloadManager.download(eq(failingUrl))).thenThrow(IllegalStateException("broken"))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.openRemoteDocument(failingUrl)
        advanceUntilIdle()

        verify(downloadManager).download(eq(failingUrl))
        val uiState = viewModel.uiState.value
        assertTrue(uiState.documentStatus is DocumentStatus.Error)
        assertEquals(
            app.getString(R.string.error_remote_open_failed),
            (uiState.documentStatus as DocumentStatus.Error).message
        )
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openRemoteDocument failure clears loading status before surfacing error`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))

        val failingUrl = "https://example.com/bad.pdf"
        whenever(downloadManager.download(eq(failingUrl))).thenReturn(Result.failure(IllegalStateException("bad")))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        val statuses = mutableListOf<DocumentStatus>()
        val job = launch {
            viewModel.uiState
                .map { it.documentStatus }
                .take(4)
                .toList(statuses)
        }

        viewModel.openRemoteDocument(failingUrl)
        advanceUntilIdle()
        job.join()

        assertEquals(DocumentStatus.Idle, statuses[0])
        assertTrue(statuses[1] is DocumentStatus.Loading)
        assertEquals(DocumentStatus.Idle, statuses[2])
        val errorStatus = statuses[3]
        assertTrue(errorStatus is DocumentStatus.Error)
        assertEquals(app.getString(R.string.error_remote_open_failed), (errorStatus as DocumentStatus.Error).message)
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openDocument failure clears loading status before surfacing error`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))

        whenever(pdfRepository.open(any())).thenThrow(PdfOpenException(PdfOpenException.Reason.CORRUPTED))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        val statuses = mutableListOf<DocumentStatus>()
        val job = launch {
            viewModel.uiState
                .map { it.documentStatus }
                .take(4)
                .toList(statuses)
        }

        val failingUri = Uri.parse("file://broken.pdf")
        viewModel.openDocument(failingUri)
        advanceUntilIdle()
        job.join()

        assertEquals(DocumentStatus.Idle, statuses[0])
        assertTrue(statuses[1] is DocumentStatus.Loading)
        assertEquals(DocumentStatus.Idle, statuses[2])
        val errorStatus = statuses[3]
        assertTrue(errorStatus is DocumentStatus.Error)
        assertEquals(app.getString(R.string.error_pdf_corrupted), (errorStatus as DocumentStatus.Error).message)
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `openRemoteDocument ignores cancellation without surfacing error`() = runTest {
        val app = TestPdfApp.getInstance()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<LuceneSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow(emptyList()))
        whenever(pdfRepository.renderProgress).thenReturn(MutableStateFlow(PdfRenderProgress.Idle))
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))

        val url = "https://example.com/cancel.pdf"
        whenever(downloadManager.download(eq(url))).thenThrow(CancellationException("cancelled"))

        app.installDependencies(
            annotationRepository,
            pdfRepository,
            adaptiveFlowManager,
            bookmarkManager,
            maintenanceScheduler,
            searchCoordinator,
            downloadManager
        )

        val viewModel = PdfViewerViewModel(app)

        viewModel.openRemoteDocument(url)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(DocumentStatus.Idle, uiState.documentStatus)
    }

}
