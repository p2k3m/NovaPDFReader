package com.novapdf.reader

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.remote.DocumentSourceGateway
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.domain.usecase.DefaultAdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.DefaultAnnotationUseCase
import com.novapdf.reader.domain.usecase.DefaultBookmarkUseCase
import com.novapdf.reader.domain.usecase.DefaultBuildSearchIndexUseCase
import com.novapdf.reader.domain.usecase.DefaultCrashReportingUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DefaultDocumentSearchUseCase
import com.novapdf.reader.domain.usecase.DefaultOpenDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultPdfViewerUseCases
import com.novapdf.reader.domain.usecase.DefaultRemoteDocumentUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderPageUseCase
import com.novapdf.reader.domain.usecase.DefaultRenderTileUseCase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import com.novapdf.reader.asTestMainDispatcher
import org.junit.Assert.assertThrows
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
class PdfViewerViewModelDispatcherTest {

    @Test
    fun renderDispatcherRejectsMainCoroutineDispatcher() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dependencies = createUseCases()
        val mainDispatcher = StandardTestDispatcher().asTestMainDispatcher()
        val dispatchers = TestCoroutineDispatchers(mainDispatcher, StandardTestDispatcher(), mainDispatcher)

        assertThrows(IllegalArgumentException::class.java) {
            PdfViewerViewModel(
                context,
                dependencies,
                dispatchers,
                PdfViewerViewModel.defaultPageBitmapCacheFactory(),
                PdfViewerViewModel.defaultTileBitmapCacheFactory(),
            )
        }
    }

    private fun createUseCases(): PdfViewerUseCases {
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<DocumentSearchCoordinator>()

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())

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
        whenever(pdfRepository.cacheFallbackActive).thenReturn(MutableStateFlow(false))

        val indexingState = MutableStateFlow<SearchIndexingState>(SearchIndexingState.Idle)
        whenever(searchCoordinator.indexingState).thenReturn(indexingState)

        val speed = MutableStateFlow(30f)
        val sensitivity = MutableStateFlow(1f)
        val preloadTargets = MutableStateFlow(emptyList<Int>())
        val frameInterval = MutableStateFlow(16.6f)
        val uiLoad = MutableStateFlow(false)
        whenever(adaptiveFlowManager.readingSpeedPagesPerMinute).thenReturn(speed)
        whenever(adaptiveFlowManager.swipeSensitivity).thenReturn(sensitivity)
        whenever(adaptiveFlowManager.preloadTargets).thenReturn(preloadTargets)
        whenever(adaptiveFlowManager.frameIntervalMillis).thenReturn(frameInterval)
        whenever(adaptiveFlowManager.uiUnderLoad).thenReturn(uiLoad)

        val crashReporter = object : CrashReporter {
            override fun install() = Unit
            override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) = Unit
            override fun logBreadcrumb(message: String) = Unit
        }

        val documentSourceGateway = object : DocumentSourceGateway {
            override suspend fun fetch(source: DocumentSource): Result<Uri> {
                return Result.failure(RemotePdfException(RemotePdfException.Reason.NETWORK))
            }
        }

        val preferencesUseCase = TestUserPreferencesUseCase()
        return DefaultPdfViewerUseCases(
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
            crashReporting = DefaultCrashReportingUseCase(crashReporter),
            adaptiveFlow = DefaultAdaptiveFlowUseCase(adaptiveFlowManager),
            preferences = preferencesUseCase,
        )
    }
}
