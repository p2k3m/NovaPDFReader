package com.novapdf.reader

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import com.novapdf.reader.ViewerBitmapCache
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.asTestMainDispatcher
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfOpenException
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
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.DomainErrorCode
import com.novapdf.reader.model.DomainException
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class PdfViewerViewModelCacheAndErrorTest {

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
    fun `trimRenderCaches clears both cache layers`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val requirePageCache = PdfViewerViewModel::class.java.getDeclaredMethod("requirePageBitmapCache").apply {
            isAccessible = true
        }
        val requireTileCache = PdfViewerViewModel::class.java.getDeclaredMethod("requireTileBitmapCache").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val pageCache = requirePageCache.invoke(viewModel) as ViewerBitmapCache<Any>
        @Suppress("UNCHECKED_CAST")
        val tileCache = requireTileCache.invoke(viewModel) as ViewerBitmapCache<Any>

        val pageKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$PageCacheKey")
        val pageKey = pageKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 256, PageRenderProfile.HIGH_DETAIL)

        val tileKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$TileCacheKey")
        val tileKey = tileKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 0, 128, 256, 512, 1f.toBits())

        val pageBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val tileBitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        pageCache.put(pageKey, pageBitmap)
        tileCache.put(tileKey, tileBitmap)

        assertTrue(pageCache.size() > 0)
        assertTrue(tileCache.size() > 0)

        val trimMethod = PdfViewerViewModel::class.java.getDeclaredMethod(
            "trimRenderCaches",
            Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        trimMethod.invoke(viewModel, 0f)

        assertEquals(0, pageCache.size())
        assertEquals(0, tileCache.size())
        assertTrue(pageBitmap.isRecycled)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `memory callbacks clear caches on low memory`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val requirePageCache = PdfViewerViewModel::class.java.getDeclaredMethod("requirePageBitmapCache").apply {
            isAccessible = true
        }
        val requireTileCache = PdfViewerViewModel::class.java.getDeclaredMethod("requireTileBitmapCache").apply {
            isAccessible = true
        }
        val callbacksField = PdfViewerViewModel::class.java.getDeclaredField("memoryCallbacks").apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val pageCache = requirePageCache.invoke(viewModel) as ViewerBitmapCache<Any>
        @Suppress("UNCHECKED_CAST")
        val tileCache = requireTileCache.invoke(viewModel) as ViewerBitmapCache<Any>
        val callbacks = callbacksField.get(viewModel) as ComponentCallbacks2

        val pageKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$PageCacheKey")
        val pageKey = pageKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 256, PageRenderProfile.HIGH_DETAIL)
        val tileKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$TileCacheKey")
        val tileKey = tileKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 0, 128, 256, 512, 1f.toBits())

        val pageBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val tileBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        pageCache.put(pageKey, pageBitmap)
        tileCache.put(tileKey, tileBitmap)

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        assertEquals(0, pageCache.size())
        assertEquals(0, tileCache.size())

        pageBitmap.recycle()
        tileBitmap.recycle()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `memory callbacks trim caches when UI hidden`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val requirePageCache = PdfViewerViewModel::class.java.getDeclaredMethod("requirePageBitmapCache").apply {
            isAccessible = true
        }
        val requireTileCache = PdfViewerViewModel::class.java.getDeclaredMethod("requireTileBitmapCache").apply {
            isAccessible = true
        }
        val callbacksField = PdfViewerViewModel::class.java.getDeclaredField("memoryCallbacks").apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val pageCache = requirePageCache.invoke(viewModel) as ViewerBitmapCache<Any>
        @Suppress("UNCHECKED_CAST")
        val tileCache = requireTileCache.invoke(viewModel) as ViewerBitmapCache<Any>
        val callbacks = callbacksField.get(viewModel) as ComponentCallbacks2

        val pageKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$PageCacheKey")
        val pageKey1 = pageKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 256, PageRenderProfile.HIGH_DETAIL)
        val pageKey2 = pageKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 1, 256, PageRenderProfile.HIGH_DETAIL)
        val tileKeyClass = Class.forName("com.novapdf.reader.PdfViewerViewModel\$TileCacheKey")
        val tileKey1 = tileKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 0, 0, 128, 256, 512, 1f.toBits())
        val tileKey2 = tileKeyClass.getDeclaredConstructors().single().apply { isAccessible = true }
            .newInstance("doc", 1, 0, 128, 256, 512, 1f.toBits())

        val pageBitmap1 = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val pageBitmap2 = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val tileBitmap1 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val tileBitmap2 = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        pageCache.put(pageKey1, pageBitmap1)
        pageCache.put(pageKey2, pageBitmap2)
        tileCache.put(tileKey1, tileBitmap1)
        tileCache.put(tileKey2, tileBitmap2)

        val initialPageSize = pageCache.size()
        val initialTileSize = tileCache.size()

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertTrue(pageCache.size() < initialPageSize)
        assertTrue(tileCache.size() < initialTileSize)
        assertTrue(pageCache.evictionCount() > 0)
        assertTrue(tileCache.evictionCount() > 0)

        pageBitmap1.recycle()
        pageBitmap2.recycle()
        tileBitmap1.recycle()
        tileBitmap2.recycle()
    }

    @Test
    fun `resolveErrorMessageRes maps known failures to string resources`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val method = PdfViewerViewModel::class.java.getDeclaredMethod(
            "resolveErrorMessageRes",
            Throwable::class.java,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val fallback = R.string.error_document_open_generic

        val pdfResult = method.invoke(
            viewModel,
            PdfOpenException(PdfOpenException.Reason.CORRUPTED),
            fallback,
        ) as Int
        val remoteResult = method.invoke(
            viewModel,
            RemotePdfException(RemotePdfException.Reason.FILE_TOO_LARGE),
            fallback,
        ) as Int
        val domainResult = method.invoke(
            viewModel,
            DomainException(DomainErrorCode.RENDER_OOM),
            fallback,
        ) as Int
        val fallbackResult = method.invoke(
            viewModel,
            IllegalStateException("unexpected"),
            fallback,
        ) as Int

        assertEquals(R.string.error_pdf_corrupted, pdfResult)
        assertEquals(R.string.error_remote_pdf_too_large, remoteResult)
        assertEquals(R.string.error_document_render_limit, domainResult)
        assertEquals(fallback, fallbackResult)
    }

    @Test
    fun `cache fallback surfaces UI notification`() = runTest {
        val cacheFallback = MutableStateFlow(false)
        val viewModel = createViewModel(cacheFallback)
        advanceUntilIdle()

        val messages = mutableListOf<Int>()
        val job = launch {
            viewModel.messageEvents.collect { message ->
                messages += message.messageRes
            }
        }

        cacheFallback.value = true
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.renderCacheFallbackActive)
        assertTrue(viewModel.uiState.value.renderCircuitBreakerActive)
        assertTrue(messages.contains(R.string.error_render_cache_unavailable))
        assertTrue(messages.contains(R.string.error_render_circuit_disabled))

        job.cancel()
    }

    private fun createViewModel(
        cacheFallback: MutableStateFlow<Boolean> = MutableStateFlow(false),
    ): PdfViewerViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val annotationRepository = mock<AnnotationRepository>()
        val pdfRepository = mock<PdfDocumentRepository>()
        val adaptiveFlowManager = mock<AdaptiveFlowManager>()
        val bookmarkManager = mock<BookmarkManager>()
        val maintenanceScheduler = mock<DocumentMaintenanceScheduler>()
        val searchCoordinator = mock<DocumentSearchCoordinator>()

        whenever(annotationRepository.annotationsForDocument(any())).thenReturn(emptyList())
        runBlocking {
            whenever(bookmarkManager.bookmarks(any())).thenReturn(emptyList())
        }

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
        whenever(pdfRepository.cacheFallbackActive).thenReturn(cacheFallback)

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
            override suspend fun fetch(source: DocumentSource): Result<android.net.Uri> {
                return Result.failure(RemotePdfException(RemotePdfException.Reason.NETWORK))
            }
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
            crashReporting = DefaultCrashReportingUseCase(crashReporter),
            adaptiveFlow = DefaultAdaptiveFlowUseCase(adaptiveFlowManager),
            preferences = preferencesUseCase,
        )

        val dispatchers = TestCoroutineDispatchers(dispatcher, dispatcher, mainDispatcher)
        return PdfViewerViewModel(
            context,
            useCases,
            dispatchers,
            PdfViewerViewModel.defaultPageBitmapCacheFactory(),
            PdfViewerViewModel.defaultTileBitmapCacheFactory(),
        )
    }
}
