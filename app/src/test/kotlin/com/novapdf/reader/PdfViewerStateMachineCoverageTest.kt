package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.asTestMainDispatcher
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
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.DomainErrorCode
import com.novapdf.reader.model.DomainException
import com.novapdf.reader.model.FallbackMode
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PdfViewerStateMachineCoverageTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val mainDispatcher = dispatcher.asTestMainDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `handleCacheStress returns NONE for non stress throwables`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        val outcome = invokeHandleCacheStress(
            harness.viewModel,
            stage = "renderTile",
            throwable = IllegalArgumentException("not cache"),
            pageIndex = null,
        )

        assertEquals(cacheOutcome("NONE"), outcome)
        assertEquals(0, getCacheFaultCount(harness.viewModel))
        assertTrue(harness.pageCache.trimFractions.isEmpty())
        assertEquals(0, harness.pageCache.evictAllCount)
    }

    @Test
    fun `handleCacheStress trims caches before escalating`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        val first = invokeHandleCacheStress(
            harness.viewModel,
            stage = "renderTile",
            throwable = OutOfMemoryError("stage1"),
            pageIndex = 3,
        )
        val second = invokeHandleCacheStress(
            harness.viewModel,
            stage = "renderTile",
            throwable = OutOfMemoryError("stage2"),
            pageIndex = 3,
        )
        val third = invokeHandleCacheStress(
            harness.viewModel,
            stage = "renderTile",
            throwable = OutOfMemoryError("stage3"),
            pageIndex = 3,
        )
        advanceUntilIdle()

        assertEquals(cacheOutcome("RETRY"), first)
        assertEquals(cacheOutcome("RETRY"), second)
        assertEquals(cacheOutcome("ESCALATED"), third)

        assertEquals(listOf(0.75f, 0.5f), harness.pageCache.trimFractions)
        assertEquals(listOf(0.75f, 0.5f), harness.tileCache.trimFractions)
        assertEquals(1, harness.pageCache.evictAllCount)
        assertEquals(1, harness.tileCache.evictAllCount)
        assertTrue(getCacheCircuitForced(harness.viewModel))
        assertTrue(harness.viewModel.uiState.value.renderCircuitBreakerActive)
        assertEquals(FallbackMode.LEGACY_SIMPLE_RENDERER, harness.viewModel.uiState.value.fallbackMode)
    }

    @Test
    fun `recordRenderFault ignores malformed pages`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        invokeRecordRenderFault(
            harness.viewModel,
            throwable = com.novapdf.reader.data.PdfRenderException(
                com.novapdf.reader.data.PdfRenderException.Reason.MALFORMED_PAGE
            ),
            stage = "renderTile",
            pageIndex = 5,
            bypassCacheCircuit = false,
        )

        assertEquals(0, getRenderFaultStreak(harness.viewModel))
        assertFalse(harness.viewModel.uiState.value.renderCircuitBreakerActive)
    }

    @Test
    fun `recordRenderFault respects cache circuit bypass`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        invokeRecordRenderFault(
            harness.viewModel,
            throwable = OutOfMemoryError("oom"),
            stage = "renderTile",
            pageIndex = 2,
            bypassCacheCircuit = true,
        )

        assertFalse(harness.viewModel.uiState.value.renderCircuitBreakerActive)
        assertEquals(0, harness.pageCache.evictAllCount)
    }

    @Test
    fun `recordRenderFault opens circuit on render oom`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        invokeRecordRenderFault(
            harness.viewModel,
            throwable = OutOfMemoryError("oom"),
            stage = "renderTile",
            pageIndex = 7,
            bypassCacheCircuit = false,
        )
        advanceUntilIdle()

        assertTrue(harness.viewModel.uiState.value.renderCircuitBreakerActive)
        assertEquals(FallbackMode.LEGACY_SIMPLE_RENDERER, harness.viewModel.uiState.value.fallbackMode)
        assertEquals(1, harness.pageCache.evictAllCount)
        assertEquals(1, harness.tileCache.evictAllCount)
    }

    @Test
    fun `recordRenderFault activates safety lock after repeated failures`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)

        repeat(3) {
            invokeRecordRenderFault(
                harness.viewModel,
                throwable = IllegalStateException("render-failure-$it"),
                stage = "renderTile",
                pageIndex = 0,
                bypassCacheCircuit = false,
            )
        }
        advanceUntilIdle()

        assertTrue(harness.viewModel.uiState.value.renderCircuitBreakerActive)
        assertTrue(getRenderSafetyLockActive(harness.viewModel))
        assertTrue(getRenderCircuitSoftActive(harness.viewModel))
    }

    @Test
    fun `recordRenderFault escalates repeat failures while circuit active`() = runTest {
        val harness = createHarness()
        instantiateCaches(harness.viewModel)
        setRenderCircuitSoftActive(harness.viewModel, true)

        invokeRecordRenderFault(
            harness.viewModel,
            throwable = IllegalStateException("repeat"),
            stage = "renderTile",
            pageIndex = 1,
            bypassCacheCircuit = false,
        )
        advanceUntilIdle()

        assertTrue(harness.viewModel.uiState.value.renderCircuitBreakerActive)
        assertTrue(getRenderSafetyLockActive(harness.viewModel))
    }

    private fun invokeHandleCacheStress(
        viewModel: PdfViewerViewModel,
        stage: String,
        throwable: Throwable?,
        pageIndex: Int?,
    ): Any? {
        val method = PdfViewerViewModel::class.java.getDeclaredMethod(
            "handleCacheStress",
            String::class.java,
            Throwable::class.java,
            Integer::class.javaObjectType,
        )
        method.isAccessible = true
        return method.invoke(viewModel, stage, throwable, pageIndex)
    }

    private fun invokeRecordRenderFault(
        viewModel: PdfViewerViewModel,
        throwable: Throwable?,
        stage: String,
        pageIndex: Int?,
        bypassCacheCircuit: Boolean,
    ) {
        val method = PdfViewerViewModel::class.java.getDeclaredMethod(
            "recordRenderFault",
            Throwable::class.java,
            String::class.java,
            Integer::class.javaObjectType,
            java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        method.invoke(viewModel, throwable, stage, pageIndex, bypassCacheCircuit)
    }

    private fun instantiateCaches(viewModel: PdfViewerViewModel) {
        val requirePage = PdfViewerViewModel::class.java.getDeclaredMethod("requirePageBitmapCache")
        requirePage.isAccessible = true
        requirePage.invoke(viewModel)
        val requireTile = PdfViewerViewModel::class.java.getDeclaredMethod("requireTileBitmapCache")
        requireTile.isAccessible = true
        requireTile.invoke(viewModel)
    }

    private fun cacheOutcome(name: String): Any {
        val clazz = Class.forName("com.novapdf.reader.PdfViewerViewModel\$CacheStressOutcome")
        @Suppress("UNCHECKED_CAST")
        return java.lang.Enum.valueOf(clazz as Class<out Enum<*>>, name)
    }

    private fun getCacheFaultCount(viewModel: PdfViewerViewModel): Int {
        val field = PdfViewerViewModel::class.java.getDeclaredField("cacheFaultCount")
        field.isAccessible = true
        return field.getInt(viewModel)
    }

    private fun getCacheCircuitForced(viewModel: PdfViewerViewModel): Boolean {
        val field = PdfViewerViewModel::class.java.getDeclaredField("cacheCircuitForced")
        field.isAccessible = true
        return field.getBoolean(viewModel)
    }

    private fun getRenderFaultStreak(viewModel: PdfViewerViewModel): Int {
        val field = PdfViewerViewModel::class.java.getDeclaredField("renderFaultStreak")
        field.isAccessible = true
        return field.getInt(viewModel)
    }

    private fun getRenderSafetyLockActive(viewModel: PdfViewerViewModel): Boolean {
        val field = PdfViewerViewModel::class.java.getDeclaredField("renderSafetyLockActive")
        field.isAccessible = true
        return field.getBoolean(viewModel)
    }

    private fun getRenderCircuitSoftActive(viewModel: PdfViewerViewModel): Boolean {
        val field = PdfViewerViewModel::class.java.getDeclaredField("renderCircuitSoftActive")
        field.isAccessible = true
        return field.getBoolean(viewModel)
    }

    private fun setRenderCircuitSoftActive(viewModel: PdfViewerViewModel, value: Boolean) {
        val field = PdfViewerViewModel::class.java.getDeclaredField("renderCircuitSoftActive")
        field.isAccessible = true
        field.setBoolean(viewModel, value)
    }

    private data class ViewModelHarness(
        val viewModel: PdfViewerViewModel,
        val pageCache: RecordingBitmapCache<PageCacheKey>,
        val tileCache: RecordingBitmapCache<TileCacheKey>,
    )

    private fun createHarness(): ViewModelHarness {
        val context = ApplicationProvider.getApplicationContext<Application>()
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
            override fun fetch(source: DocumentSource) = flowOf<RemoteDocumentFetchEvent>(
                RemoteDocumentFetchEvent.Failure(RemotePdfException(RemotePdfException.Reason.NETWORK))
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
            crashReporting = DefaultCrashReportingUseCase(crashReporter),
            adaptiveFlow = DefaultAdaptiveFlowUseCase(adaptiveFlowManager),
            preferences = preferencesUseCase,
            contractRegistry = createTestModuleContractsRegistry(),
        )

        val dispatchers = TestCoroutineDispatchers(dispatcher, dispatcher, mainDispatcher)

        val pageFactory = RecordingCacheFactory<PageCacheKey>()
        val tileFactory = RecordingCacheFactory<TileCacheKey>()

        val viewModel = PdfViewerViewModel(
            context,
            useCases,
            dispatchers,
            pageFactory,
            tileFactory,
        )

        return ViewModelHarness(viewModel, pageFactory.cache, tileFactory.cache)
    }

    private class RecordingCacheFactory<K : Any> : ViewerBitmapCacheFactory<K> {
        lateinit var cache: RecordingBitmapCache<K>
            private set

        override fun create(maxBytes: Int, sizeCalculator: (Bitmap) -> Int): ViewerBitmapCache<K> {
            return RecordingBitmapCache<K>().also { cache = it }
        }
    }

    private class RecordingBitmapCache<K : Any> : ViewerBitmapCache<K> {
        val trimFractions = mutableListOf<Float>()
        var evictAllCount: Int = 0

        override fun get(key: K): Bitmap? = null

        override fun put(key: K, value: Bitmap): Bitmap? = null

        override fun remove(key: K): Bitmap? = null

        override fun evictAll() {
            evictAllCount += 1
        }

        override fun trimToFraction(fraction: Float) {
            trimFractions += fraction
        }

        override fun size(): Int = 0

        override fun maxSize(): Int = 0

        override fun hitCount(): Int = 0

        override fun missCount(): Int = 0

        override fun putCount(): Int = 0

        override fun evictionCount(): Int = evictAllCount
    }
}
