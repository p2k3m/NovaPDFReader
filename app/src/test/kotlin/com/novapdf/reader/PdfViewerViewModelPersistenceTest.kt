package com.novapdf.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.asTestMainDispatcher
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.domain.usecase.DefaultAdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.DefaultAnnotationUseCase
import com.novapdf.reader.domain.usecase.DefaultBookmarkUseCase
import com.novapdf.reader.domain.usecase.DefaultBuildSearchIndexUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentSearchUseCase
import com.novapdf.reader.domain.usecase.DefaultOpenDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfViewerUseCases
import com.novapdf.reader.domain.usecase.DefaultRemoteDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderPageUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderTileUseCase
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class PdfViewerViewModelPersistenceTest {

    private val dispatcher = StandardTestDispatcher()
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
    fun `onPageSettled throttles viewport persistence`() = runTest {
        val (viewModel, preferencesUseCase, sessionFlow) = createViewModel()
        val session = PdfDocumentSession(
            documentId = "doc",
            uri = Uri.parse("file://doc"),
            pageCount = 5,
            document = mock(),
            fileDescriptor = mock(),
        )
        sessionFlow.value = session
        advanceUntilIdle()

        viewModel.onPageSettled(1)
        viewModel.onPageSettled(2)

        advanceTimeBy(700)
        assertTrue(preferencesUseCase.lastViewportRequests.isEmpty())

        advanceTimeBy(100)
        advanceUntilIdle()

        assertEquals(listOf(2 to 1f), preferencesUseCase.lastViewportRequests)
    }

    private suspend fun createViewModel(): Triple<PdfViewerViewModel, TestUserPreferencesUseCase, MutableStateFlow<PdfDocumentSession?>> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<DocumentSearchCoordinator>()

        val renderProgress = MutableStateFlow<PdfRenderProgress>(PdfRenderProgress.Idle)
        val sessionFlow = MutableStateFlow<PdfDocumentSession?>(null)
        val outlineFlow = MutableStateFlow<List<PdfOutlineNode>>(emptyList())
        val bitmapStats = MutableStateFlow(
            BitmapMemoryStats(
                currentBytes = 0,
                peakBytes = 0,
                warnThresholdBytes = 0,
                criticalThresholdBytes = 0,
                level = BitmapMemoryLevel.NORMAL,
            )
        )

        whenever(pdfRepository.renderProgress).thenReturn(renderProgress)
        whenever(pdfRepository.session).thenReturn(sessionFlow)
        whenever(pdfRepository.outline).thenReturn(outlineFlow)
        whenever(pdfRepository.bitmapMemory).thenReturn(bitmapStats)

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())

        val indexingState = MutableStateFlow<SearchIndexingState>(SearchIndexingState.Idle)
        whenever(searchCoordinator.indexingState).thenReturn(indexingState)

        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(MutableStateFlow(30f))
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(MutableStateFlow(1f))
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(MutableStateFlow(emptyList()))
        whenever(adaptiveFlowManager.frameIntervalMillis).thenReturn(MutableStateFlow(16.6f))
        whenever(adaptiveFlowManager.uiUnderLoad).thenReturn(MutableStateFlow(false))

        val crashReporter = object : CrashReporter {
            override fun install() = Unit
            override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) = Unit
            override fun logBreadcrumb(message: String) = Unit
        }

        val documentSourceGateway = object : com.novapdf.reader.data.remote.DocumentSourceGateway {
            override fun fetch(source: DocumentSource) = flowOf<RemoteDocumentFetchEvent>(
                RemoteDocumentFetchEvent.Failure(
                    RemotePdfException(RemotePdfException.Reason.NETWORK, IllegalStateException("unsupported"))
                )
            )
        }

        val preferencesUseCase = TestUserPreferencesUseCase()

        val useCases = DefaultPdfViewerUseCases(
            document = DefaultPdfDocumentUseCase(pdfRepository),
            openDocument = DefaultOpenDocumentUseCase(pdfRepository),
            renderPage = DefaultRenderPageUseCase(pdfRepository),
            renderTile = DefaultRenderTileUseCase(pdfRepository),
            annotations = DefaultAnnotationUseCase(annotationRepository),
            bookmarks = DefaultBookmarkUseCase(bookmarkManager),
            search = DefaultDocumentSearchUseCase(searchCoordinator),
            buildSearchIndex = DefaultBuildSearchIndexUseCase(searchCoordinator),
            remoteDocuments = DefaultRemoteDocumentUseCase(documentSourceGateway),
            maintenance = DefaultDocumentMaintenanceUseCase(maintenanceScheduler),
            crashReporting = com.novapdf.reader.domain.usecase.DefaultCrashReportingUseCase(crashReporter),
            adaptiveFlow = DefaultAdaptiveFlowUseCase(adaptiveFlowManager),
            preferences = preferencesUseCase,
        )

        val dispatchers = TestCoroutineDispatchers(dispatcher, dispatcher, mainDispatcher)
        val viewModel = PdfViewerViewModel(
            context,
            useCases,
            dispatchers,
            PdfViewerViewModel.defaultPageBitmapCacheFactory(),
            PdfViewerViewModel.defaultTileBitmapCacheFactory(),
        )
        return Triple(viewModel, preferencesUseCase, sessionFlow)
    }
}
