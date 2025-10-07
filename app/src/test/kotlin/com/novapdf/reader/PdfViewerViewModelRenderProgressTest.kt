package com.novapdf.reader

import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.novapdf.reader.engine.AdaptiveFlowManager
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PdfViewerViewModelRenderProgressTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Config(application = TestPdfApp::class)
    fun `uiState mirrors repository render progress`() = runTest {
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<DocumentSearchCoordinator>()
        val downloadManager = mock<PdfDownloadManager>()

        val renderProgress = MutableStateFlow<PdfRenderProgress>(PdfRenderProgress.Idle)
        whenever(pdfRepository.renderProgress).thenReturn(renderProgress)
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow<List<PdfOutlineNode>>(emptyList()))

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))

        val app = TestPdfApp.getInstance()
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

        renderProgress.value = PdfRenderProgress.Rendering(pageIndex = 2, progress = 0.4f)
        advanceUntilIdle()
        assertEquals(PdfRenderProgress.Rendering(2, 0.4f), viewModel.uiState.value.renderProgress)

        renderProgress.value = PdfRenderProgress.Idle
        advanceUntilIdle()
        assertEquals(PdfRenderProgress.Idle, viewModel.uiState.value.renderProgress)
    }
}
