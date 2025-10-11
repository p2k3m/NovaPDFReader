package com.novapdf.reader

import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import android.net.Uri
import com.novapdf.reader.data.remote.DocumentSourceGateway
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.domain.usecase.DefaultAdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.DefaultAnnotationUseCase
import com.novapdf.reader.domain.usecase.DefaultBookmarkUseCase
import com.novapdf.reader.domain.usecase.DefaultCrashReportingUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentSearchUseCase
import com.novapdf.reader.domain.usecase.DefaultBuildSearchIndexUseCase
import com.novapdf.reader.domain.usecase.DefaultOpenDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfViewerUseCases
import com.novapdf.reader.domain.usecase.DefaultRemoteDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderPageUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderTileUseCase
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
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
    private val mainDispatcher = dispatcher.asTestMainDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
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

        val renderProgress = MutableStateFlow<PdfRenderProgress>(PdfRenderProgress.Idle)
        whenever(pdfRepository.renderProgress).thenReturn(renderProgress)
        whenever(pdfRepository.session).thenReturn(MutableStateFlow<PdfDocumentSession?>(null))
        whenever(pdfRepository.outline).thenReturn(MutableStateFlow<List<PdfOutlineNode>>(emptyList()))
        whenever(searchCoordinator.indexingState).thenReturn(MutableStateFlow(SearchIndexingState.Idle))

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(adaptiveFlowManager.frameIntervalMillis).thenReturn(MutableStateFlow(16.6f))

        val crashReporter = object : CrashReporter {
            override fun install() = Unit
            override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) = Unit
            override fun logBreadcrumb(message: String) = Unit
        }

        val openDocumentUseCase = DefaultOpenDocumentUseCase(pdfRepository)
        val renderPageUseCase = DefaultRenderPageUseCase(pdfRepository)
        val renderTileUseCase = DefaultRenderTileUseCase(pdfRepository)
        val buildIndexUseCase = DefaultBuildSearchIndexUseCase(searchCoordinator)

        val documentSourceGateway = object : DocumentSourceGateway {
            override suspend fun fetch(source: DocumentSource): Result<Uri> {
                return Result.failure(RemotePdfException(RemotePdfException.Reason.NETWORK))
            }
        }

        val useCases = DefaultPdfViewerUseCases(
            document = DefaultPdfDocumentUseCase(pdfRepository),
            openDocument = openDocumentUseCase,
            renderPage = renderPageUseCase,
            renderTile = renderTileUseCase,
            annotations = DefaultAnnotationUseCase(annotationRepository),
            bookmarks = DefaultBookmarkUseCase(bookmarkManager),
            search = DefaultDocumentSearchUseCase(searchCoordinator),
            buildSearchIndex = buildIndexUseCase,
            remoteDocuments = DefaultRemoteDocumentUseCase(documentSourceGateway),
            maintenance = DefaultDocumentMaintenanceUseCase(maintenanceScheduler),
            crashReporting = DefaultCrashReportingUseCase(crashReporter),
            adaptiveFlow = DefaultAdaptiveFlowUseCase(adaptiveFlowManager)
        )

        val app = ApplicationProvider.getApplicationContext<TestPdfApp>()
        val viewModel = PdfViewerViewModel(
            app,
            useCases,
            TestCoroutineDispatchers(dispatcher, dispatcher, mainDispatcher)
        )

        renderProgress.value = PdfRenderProgress.Rendering(pageIndex = 2, progress = 0.4f)
        advanceUntilIdle()
        assertEquals(PdfRenderProgress.Rendering(2, 0.4f), viewModel.uiState.value.renderProgress)

        renderProgress.value = PdfRenderProgress.Idle
        advanceUntilIdle()
        assertEquals(PdfRenderProgress.Idle, viewModel.uiState.value.renderProgress)
    }
}
