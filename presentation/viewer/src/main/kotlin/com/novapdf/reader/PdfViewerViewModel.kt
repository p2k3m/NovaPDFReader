package com.novapdf.reader

import android.app.Application
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Size
import android.util.LruCache
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.annotation.StringRes
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfOpenException
import com.novapdf.reader.data.PdfRenderException
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.data.remote.RemotePdfTooLargeException
import com.novapdf.reader.data.remote.RemoteSourceDiagnostics
import com.novapdf.reader.domain.usecase.BuildSearchIndexRequest
import com.novapdf.reader.domain.usecase.OpenDocumentRequest
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RenderPageRequest
import com.novapdf.reader.domain.usecase.RenderTileRequest
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.DomainErrorCode
import com.novapdf.reader.model.DomainException
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.presentation.viewer.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.min

private const val TAG = "PdfViewerViewModel"
private const val DEFAULT_THEME_SEED_COLOR = 0xFFD32F2FL
const val LARGE_DOCUMENT_PAGE_THRESHOLD = 400
private const val RENDER_POOL_PARALLELISM = 2
private const val INDEX_POOL_PARALLELISM = 1
private const val MAX_PAGE_CACHE_BYTES = 32 * 1024 * 1024
private const val MAX_TILE_CACHE_BYTES = 24 * 1024 * 1024
private const val MIN_CACHE_BYTES = 1 * 1024 * 1024
private const val REMOTE_PDF_SAFE_SIZE_BYTES = 256L * 1024L * 1024L
private const val VIEWPORT_PERSIST_THROTTLE_MS = 750L
private const val DEV_ANR_STALL_DURATION_MS = 6_000L
private const val DEV_ANR_REPEAT_DELAY_MS = 20_000L
private const val DEV_ANR_SLEEP_CHUNK_MS = 50L
sealed interface DocumentStatus {
    object Idle : DocumentStatus
    data class Loading(
        val progress: Float?,
        @StringRes val messageRes: Int?
    ) : DocumentStatus

    data class Error(val message: String) : DocumentStatus
}

data class UiMessage(
    val id: Long = System.currentTimeMillis(),
    @StringRes val messageRes: Int
)

data class PendingLargeDownload(
    val source: DocumentSource,
    val sizeBytes: Long,
    val maxBytes: Long,
)

data class PdfViewerUiState(
    val documentId: String? = null,
    val lastOpenedDocumentUri: String? = null,
    val lastOpenedDocumentPageIndex: Int? = null,
    val lastOpenedDocumentZoom: Float? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val documentStatus: DocumentStatus = DocumentStatus.Idle,
    val preferencesReady: Boolean = false,
    val isNightMode: Boolean = false,
    val readingSpeed: Float = 0f,
    val swipeSensitivity: Float = 1f,
    val frameIntervalMillis: Float = 16.6f,
    val uiUnderLoad: Boolean = false,
    val searchResults: List<SearchResult> = emptyList(),
    val activeAnnotations: List<AnnotationCommand> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    val dynamicColorEnabled: Boolean = true,
    val highContrastEnabled: Boolean = false,
    val talkBackIntegrationEnabled: Boolean = false,
    val fontScale: Float = 1f,
    val themeSeedColor: Long = DEFAULT_THEME_SEED_COLOR,
    val outline: List<PdfOutlineNode> = emptyList(),
    val searchIndexing: SearchIndexingState = SearchIndexingState.Idle,
    val renderProgress: PdfRenderProgress = PdfRenderProgress.Idle,
    val bitmapMemory: BitmapMemoryStats = BitmapMemoryStats(),
    val renderQueueStats: RenderQueueStats = RenderQueueStats(),
    val malformedPages: Set<Int> = emptySet(),
    val pendingLargeDownload: PendingLargeDownload? = null,
    val devDiagnosticsEnabled: Boolean = BuildConfig.DEBUG,
    val devCachesEnabled: Boolean = true,
    val devArtificialDelayEnabled: Boolean = false,
)

private data class DocumentContext(
    val speed: Float,
    val sensitivity: Float,
    val frameIntervalMillis: Float,
    val isUiUnderLoad: Boolean,
    val session: PdfDocumentSession?,
    val outline: List<PdfOutlineNode>,
    val renderProgress: PdfRenderProgress,
)

private data class PrefetchRequest(
    val indices: List<Int>,
    val widthPx: Int
)

private data class ViewportCommit(
    val documentId: String,
    val pageIndex: Int,
    val zoom: Float,
)

private data class PageCacheKey(
    val documentId: String,
    val pageIndex: Int,
    val widthPx: Int,
    val profile: PageRenderProfile,
)

private data class TileCacheKey(
    val documentId: String,
    val pageIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val scaleBits: Int,
)

private data class CacheStats(
    val sizeBytes: Int,
    val maxSizeBytes: Int,
    val hitCount: Int,
    val missCount: Int,
    val putCount: Int,
    val evictionCount: Int,
)

@HiltViewModel
open class PdfViewerViewModel @Inject constructor(
    application: Application,
    private val useCases: PdfViewerUseCases,
    private val dispatchers: CoroutineDispatchers,
) : AndroidViewModel(application) {
    private val app: Application = application
    private val documentUseCase = useCases.document
    private val openDocumentUseCase = useCases.openDocument
    private val renderPageUseCase = useCases.renderPage
    private val renderTileUseCase = useCases.renderTile
    private val annotationUseCase = useCases.annotations
    private val bookmarkUseCase = useCases.bookmarks
    private val searchUseCase = useCases.search
    private val buildSearchIndexUseCase = useCases.buildSearchIndex
    private val remoteDocumentUseCase = useCases.remoteDocuments
    private val maintenanceUseCase = useCases.maintenance
    private val crashReportingUseCase = useCases.crashReporting
    private val adaptiveFlowUseCase = useCases.adaptiveFlow
    private val preferencesUseCase = useCases.preferences
    private val initialNightMode: Boolean = isNightModeEnabled()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = dispatchers.io.limitedParallelism(RENDER_POOL_PARALLELISM)

    private val renderQueue = RenderWorkQueue(viewModelScope, renderDispatcher, RENDER_POOL_PARALLELISM)
    private val processLifecycleOwner = runCatching { ProcessLifecycleOwner.get() }.getOrNull()
    @Volatile
    private var appInForeground: Boolean = true
    @Volatile
    private var uiUnderLoad: Boolean = false
    @Volatile
    private var backgroundWorkEnabled: Boolean = true
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            updateForegroundState(true)
        }

        override fun onStop(owner: LifecycleOwner) {
            updateForegroundState(false)
        }
    }

    private val thumbnailRenderProfile = resolveThumbnailRenderProfile()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val indexDispatcher = dispatchers.io.limitedParallelism(INDEX_POOL_PARALLELISM)

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _messageEvents = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messageEvents: SharedFlow<UiMessage> = _messageEvents.asSharedFlow()

    private val prefetchRequests = Channel<PrefetchRequest>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var viewportCommitJob: Job? = null
    private var pendingViewportCommit: ViewportCommit? = null

    private val pageCacheLock = Any()
    private val tileCacheLock = Any()
    private val renderCachesEnabled = AtomicBoolean(true)
    private val pageCacheMaxBytes = computeCacheBudget(MAX_PAGE_CACHE_BYTES)
    private val tileCacheMaxBytes = computeCacheBudget(MAX_TILE_CACHE_BYTES)
    private val pageBitmapCache = object : LruCache<PageCacheKey, Bitmap>(pageCacheMaxBytes) {
        override fun sizeOf(key: PageCacheKey, value: Bitmap): Int = bitmapSize(value)

        fun trimToFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            when {
                clamped <= 0f -> evictAll()
                clamped >= 1f -> Unit
                else -> {
                    val currentSize = size()
                    if (currentSize > 0) {
                        val target = (currentSize.toFloat() * clamped).toInt().coerceAtLeast(0)
                        trimToSize(target)
                    }
                }
            }
        }
    }
    private val tileBitmapCache = object : LruCache<TileCacheKey, Bitmap>(tileCacheMaxBytes) {
        override fun sizeOf(key: TileCacheKey, value: Bitmap): Int = bitmapSize(value)

        fun trimToFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            when {
                clamped <= 0f -> evictAll()
                clamped >= 1f -> Unit
                else -> {
                    val currentSize = size()
                    if (currentSize > 0) {
                        val target = (currentSize.toFloat() * clamped).toInt().coerceAtLeast(0)
                        trimToSize(target)
                    }
                }
            }
        }
    }
    @Volatile
    private var activeDocumentId: String? = null
    private val artificialDelayHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var devArtificialDelayEnabled: Boolean = false
    private val artificialDelayRunnable = object : Runnable {
        override fun run() {
            if (!devArtificialDelayEnabled) return
            NovaLog.w(
                TAG,
                "Artificial ANR stall triggered",
                throwable = null,
                field("durationMs", DEV_ANR_STALL_DURATION_MS)
            )
            stallMainThread(DEV_ANR_STALL_DURATION_MS)
            if (devArtificialDelayEnabled) {
                artificialDelayHandler.postDelayed(this, DEV_ANR_REPEAT_DELAY_MS)
            }
        }
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) {
            val nightMode = isNightModeEnabled()
            if (_uiState.value.isNightMode != nightMode) {
                updateUiState { current -> current.copy(isNightMode = nightMode) }
            }
            viewModelScope.launch(dispatchers.io) {
                preferencesUseCase.setNightModeEnabled(nightMode)
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
            clearRenderCaches()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onTrimMemory(level: Int) {
            @Suppress("DEPRECATION")
            when {
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> trimRenderCaches(0.5f)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> clearRenderCaches()
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> trimRenderCaches(0.5f)
            }
        }
    }

    private var searchJob: Job? = null
    private var indexingJob: Job? = null
    private var remoteDownloadJob: Job? = null
    private var viewportWidthPx: Int = 1080
    private var prefetchEnabled: Boolean = true
    private val pageTooLargeNotified = AtomicBoolean(false)

    private suspend fun setLoadingState(
        isLoading: Boolean,
        progress: Float?,
        @StringRes messageRes: Int?,
        resetError: Boolean = false
    ) {
        val normalizedProgress = progress?.coerceIn(0f, 1f)
        updateUiState { current ->
            val status = when {
                isLoading -> DocumentStatus.Loading(normalizedProgress, messageRes)
                !resetError && current.documentStatus is DocumentStatus.Error -> current.documentStatus
                else -> DocumentStatus.Idle
            }
            current.copy(documentStatus = status)
        }
    }

    private fun resetTransientStatus() {
        updateUiState { current ->
            when (current.documentStatus) {
                is DocumentStatus.Loading,
                is DocumentStatus.Error -> current.copy(documentStatus = DocumentStatus.Idle)
                DocumentStatus.Idle -> current
            }
        }
    }

    init {
        val initialForeground = processLifecycleOwner?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.STARTED) ?: true
        updateForegroundState(initialForeground)
        processLifecycleOwner?.lifecycle?.addObserver(appLifecycleObserver)
        app.registerComponentCallbacks(memoryCallbacks)
        val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        _uiState.value = _uiState.value.copy(
            isNightMode = initialNightMode,
            dynamicColorEnabled = supportsDynamicColor
        )
        viewModelScope.launch(dispatchers.io) {
            preferencesUseCase.preferences.collect { prefs ->
                val resolvedNightMode = prefs.nightMode ?: initialNightMode
                if (prefs.nightMode == null) {
                    preferencesUseCase.setNightModeEnabled(resolvedNightMode)
                }
                withContext(dispatchers.main) {
                    updateUiState { current ->
                        current.copy(
                            isNightMode = resolvedNightMode,
                            lastOpenedDocumentUri = prefs.lastDocumentUri,
                            lastOpenedDocumentPageIndex = prefs.lastDocumentPageIndex,
                            lastOpenedDocumentZoom = prefs.lastDocumentZoom,
                            preferencesReady = true
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            val contextFlow = combine(
                adaptiveFlowUseCase.readingSpeed,
                adaptiveFlowUseCase.swipeSensitivity,
            ) { speed, sensitivity ->
                speed to sensitivity
            }.combine(adaptiveFlowUseCase.frameIntervalMillis) { (speed, sensitivity), frameInterval ->
                DocumentContext(
                    speed = speed,
                    sensitivity = sensitivity,
                    frameIntervalMillis = frameInterval,
                    isUiUnderLoad = false,
                    session = null,
                    outline = emptyList(),
                    renderProgress = PdfRenderProgress.Idle,
                )
            }.combine(documentUseCase.session) { context, session ->
                context.copy(session = session)
            }.combine(documentUseCase.outline) { context, outline ->
                context.copy(outline = outline)
            }.combine(documentUseCase.renderProgress) { context, renderProgress ->
                context.copy(renderProgress = renderProgress)
            }.combine(adaptiveFlowUseCase.uiUnderLoad) { context, uiUnderLoad ->
                context.copy(isUiUnderLoad = uiUnderLoad)
            }

            contextFlow.combine(documentUseCase.bitmapMemory) { context, bitmapMemory ->
                context to bitmapMemory
            }.collect { (context, bitmapMemory) ->
                val session = context.session
                recomputePrefetchEnabled(session)
                uiUnderLoad = context.isUiUnderLoad
                val annotations = session?.let { annotationUseCase.annotationsFor(it.documentId) }
                    .orEmpty()
                val bookmarks = if (session != null) {
                    bookmarkUseCase.bookmarksFor(session.documentId)
                } else {
                    emptyList()
                }
                val previous = _uiState.value
                val documentId = session?.documentId
                if (activeDocumentId != documentId) {
                    clearRenderCaches()
                    activeDocumentId = documentId
                }
                val shouldResetMalformed = previous.documentId != documentId
                _uiState.value = previous.copy(
                    readingSpeed = context.speed,
                    swipeSensitivity = context.sensitivity,
                    frameIntervalMillis = context.frameIntervalMillis,
                    uiUnderLoad = context.isUiUnderLoad,
                    documentId = documentId,
                    pageCount = session?.pageCount ?: 0,
                    activeAnnotations = annotations,
                    bookmarks = bookmarks,
                    outline = context.outline,
                    renderProgress = context.renderProgress,
                    bitmapMemory = bitmapMemory,
                    malformedPages = if (shouldResetMalformed) emptySet() else previous.malformedPages
                )
                updateBackgroundWorkState()
            }
        }
        viewModelScope.launch {
            searchUseCase.indexingState.collect { indexingState ->
                updateUiState { current ->
                    if (current.searchIndexing == indexingState) {
                        current
                    } else {
                        current.copy(searchIndexing = indexingState)
                    }
                }
            }
        }
        viewModelScope.launch(renderDispatcher) {
            for (request in prefetchRequests) {
                if (request.indices.isEmpty() || request.widthPx <= 0) continue
                if (shouldThrottlePrefetch()) continue
                documentUseCase.prefetchPages(request.indices, request.widthPx)
            }
        }
        viewModelScope.launch {
            adaptiveFlowUseCase.preloadTargets.collect { targets ->
                if (targets.isNotEmpty() && !shouldThrottlePrefetch()) {
                    val width = viewportWidthPx
                    if (width > 0) {
                        val sanitized = targets.filterNot(::isPageMalformed)
                        if (sanitized.isNotEmpty()) {
                            prefetchRequests.trySend(PrefetchRequest(sanitized, width)).isSuccess
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            renderQueue.stats.collect { stats ->
                updateUiState { current ->
                    if (current.renderQueueStats == stats) {
                        current
                    } else {
                        current.copy(renderQueueStats = stats)
                    }
                }
            }
        }
    }

    private fun shouldThrottlePrefetch(): Boolean {
        if (!prefetchEnabled) return true
        if (!appInForeground) return true
        return uiUnderLoad
    }

    private fun recomputePrefetchEnabled(session: PdfDocumentSession?) {
        val enabled = renderCachesEnabled.get() &&
            (session?.pageCount?.let { it <= LARGE_DOCUMENT_PAGE_THRESHOLD } ?: true)
        if (prefetchEnabled != enabled) {
            prefetchEnabled = enabled
            updateBackgroundWorkState()
        }
    }

    private fun updateForegroundState(isForeground: Boolean) {
        appInForeground = isForeground
        updateBackgroundWorkState()
    }

    private fun updateBackgroundWorkState() {
        val shouldEnable = appInForeground && prefetchEnabled && !uiUnderLoad
        if (backgroundWorkEnabled != shouldEnable) {
            backgroundWorkEnabled = shouldEnable
            renderQueue.setBackgroundWorkEnabled(shouldEnable)
        }
    }

    fun openDocument(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            loadDocument(uri, resetError = true)
        }
    }

    fun openLastDocument() {
        if (!_uiState.value.preferencesReady) return
        val uriString = _uiState.value.lastOpenedDocumentUri ?: return
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        openDocument(uri)
    }

    fun openRemoteDocument(source: DocumentSource) {
        val previousJob = remoteDownloadJob
        val newJob = viewModelScope.launch(dispatchers.io) {
            previousJob?.cancelAndJoin()
            clearPendingLargeDownload()
            setLoadingState(
                isLoading = true,
                progress = 0f,
                messageRes = R.string.loading_stage_downloading,
                resetError = true
            )
            val result = try {
                remoteDocumentUseCase.fetch(source)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    setLoadingState(
                        isLoading = false,
                        progress = null,
                        messageRes = null,
                        resetError = false
                    )
                    throw throwable
                }
                reportRemoteOpenFailure(throwable, source)
                return@launch
            }
            result.onSuccess { uri ->
                loadDocument(uri, resetError = false)
            }.onFailure { throwable ->
                if (throwable is CancellationException) {
                    setLoadingState(
                        isLoading = false,
                        progress = null,
                        messageRes = null,
                        resetError = false
                    )
                    throw throwable
                }
                val remoteFailure = throwable.findCause<RemotePdfException>()
                if (remoteFailure?.reason == RemotePdfException.Reason.FILE_TOO_LARGE) {
                    val sizeInfo = throwable.findCause<RemotePdfTooLargeException>()
                    setLoadingState(
                        isLoading = false,
                        progress = null,
                        messageRes = null,
                        resetError = false
                    )
                    promptLargeRemoteDownload(
                        source = source,
                        sizeBytes = sizeInfo?.sizeBytes ?: 0L,
                        maxBytes = sizeInfo?.maxBytes ?: REMOTE_PDF_SAFE_SIZE_BYTES,
                    )
                    return@onFailure
                }
                reportRemoteOpenFailure(throwable, source)
            }
        }
        remoteDownloadJob = newJob
        newJob.invokeOnCompletion { throwable ->
            if (remoteDownloadJob === newJob) {
                remoteDownloadJob = null
            }
            if (throwable is CancellationException && remoteDownloadJob == null) {
                viewModelScope.launch {
                    setLoadingState(
                        isLoading = false,
                        progress = null,
                        messageRes = null,
                        resetError = false
                    )
                }
            }
        }
    }

    fun cancelRemoteDocumentLoad() {
        remoteDownloadJob?.cancel()
    }

    fun confirmLargeRemoteDownload() {
        val pending = _uiState.value.pendingLargeDownload ?: return
        updateUiState { current -> current.copy(pendingLargeDownload = null) }
        enqueueMessage(R.string.remote_pdf_download_started)
        openRemoteDocument(pending.source.withLargeFileConsentFlag())
    }

    fun dismissLargeRemoteDownload() {
        clearPendingLargeDownload()
    }

    fun cancelIndexing() {
        indexingJob?.cancel()
    }

    private suspend fun loadDocument(uri: Uri, resetError: Boolean) {
        searchJob?.cancel()
        pageTooLargeNotified.set(false)
        activeDocumentId = null
        clearRenderCaches()
        viewportCommitJob?.cancel()
        pendingViewportCommit = null
        setLoadingState(
            isLoading = true,
            progress = 0f,
            messageRes = R.string.loading_stage_resolving,
            resetError = resetError
        )
        val openResult = withContext(dispatchers.io) {
            val signal = CancellationSignal()
            openDocumentUseCase(OpenDocumentRequest(uri), signal)
        }
        val sessionResult = openResult.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            handleDocumentError(throwable)
            return
        }
        val session = sessionResult.session
        runCatching {
            preferencesUseCase.setLastOpenedDocument(session.uri.toString())
            preferencesUseCase.setLastDocumentViewport(pageIndex = 0, zoom = 1f)
        }
        annotationUseCase.clear(session.documentId)
        setLoadingState(
            isLoading = true,
            progress = 0.35f,
            messageRes = R.string.loading_stage_parsing
        )
        withContext(dispatchers.main) {
            adaptiveFlowUseCase.start()
        }
        adaptiveFlowUseCase.trackPageChange(0, session.pageCount)
        preloadInitialPage()
        setLoadingState(
            isLoading = true,
            progress = 0.85f,
            messageRes = R.string.loading_stage_finalizing
        )
        val bookmarks = bookmarkUseCase.bookmarksFor(session.documentId)
        updateUiState { current ->
            current.copy(
                documentStatus = DocumentStatus.Idle,
                documentId = session.documentId,
                pageCount = session.pageCount,
                currentPage = 0,
                activeAnnotations = emptyList(),
                bookmarks = bookmarks,
                outline = documentUseCase.outline.value,
                searchResults = emptyList()
            )
        }
        indexingJob?.cancel()
        val indexingSignal = CancellationSignal()
        val indexResult = withContext(indexDispatcher) {
            buildSearchIndexUseCase(
                BuildSearchIndexRequest(session),
                indexingSignal,
            )
        }
        indexResult.onSuccess { result ->
            indexingJob = result.job
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            if (throwable is DomainException && throwable.code == DomainErrorCode.IO_TIMEOUT) {
                notifyOperationTimeout()
            }
            crashReportingUseCase.recordNonFatal(
                throwable,
                mapOf(
                    "stage" to "searchIndex",
                    "documentId" to session.documentId
                ),
            )
        }
    }

    private suspend fun preloadInitialPage() {
        setLoadingState(
            isLoading = true,
            progress = 0.55f,
            messageRes = R.string.loading_stage_rendering
        )
        val targetWidth = viewportWidthPx.coerceAtLeast(480)
        withContext(renderDispatcher) {
            val signal = CancellationSignal()
            val result = renderPageUseCase(
                RenderPageRequest(pageIndex = 0, targetWidth = targetWidth),
                signal,
            )
            val throwable = result.exceptionOrNull()
            if (throwable is CancellationException) throw throwable
            if (throwable is DomainException && throwable.code == DomainErrorCode.IO_TIMEOUT) {
                notifyOperationTimeout()
            }
            if (throwable is PdfRenderException && throwable.reason == PdfRenderException.Reason.PAGE_TOO_LARGE) {
                notifyPageTooLarge()
                val fallbackWidth = throwable.suggestedWidth?.takeIf { it > 0 }
                if (fallbackWidth != null) {
                    val fallbackSignal = CancellationSignal()
                    val fallbackResult = renderPageUseCase(
                        RenderPageRequest(pageIndex = 0, targetWidth = fallbackWidth),
                        fallbackSignal,
                    )
                    fallbackResult.exceptionOrNull()?.let { error ->
                        if (error is CancellationException) throw error
                        if (error is DomainException && error.code == DomainErrorCode.IO_TIMEOUT) {
                            notifyOperationTimeout()
                        }
                    }
                }
            }
        }
        setLoadingState(
            isLoading = true,
            progress = 0.7f,
            messageRes = R.string.loading_stage_rendering
        )
    }

    private fun updateUiState(transform: (PdfViewerUiState) -> PdfViewerUiState) {
        _uiState.update(transform)
    }

    private suspend fun handleDocumentError(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        resetTransientStatus()
        val metadata = buildMap {
            put("stage", "documentOpen")
            val reason = (throwable as? PdfOpenException)?.reason?.name ?: "generic"
            put("reason", reason)
            _uiState.value.documentId?.let { put("documentId", it) }
        }
        crashReportingUseCase.recordNonFatal(throwable, metadata)
        val messageRes = resolveErrorMessageRes(
            throwable = throwable,
            pdfFallback = R.string.error_document_open_generic,
        )
        showError(app.getString(messageRes))
    }

    fun reportRemoteOpenFailure(throwable: Throwable, source: DocumentSource? = null) {
        viewModelScope.launch {
            resetTransientStatus()
            val remote = throwable.findCause<RemotePdfException>()
            if (throwable !is CancellationException) {
                val metadata = buildMap {
                    put("stage", "remoteDownload")
                    source?.let {
                        put("sourceKind", it.kind.name)
                        put("sourceId", it.id)
                    }
                    remote?.let { error ->
                        put("reason", error.reason.name)
                        error.diagnostics?.let { diagnostics ->
                            put("failureCount", diagnostics.failureCount.toString())
                            diagnostics.lastFailureReason?.let { lastReason ->
                                put("lastFailureReason", lastReason.name)
                            }
                            diagnostics.lastFailureMessage
                                ?.let(::sanitizeDiagnosticsMessage)
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("lastFailureMessage", it) }
                        }
                    }
                }
                crashReportingUseCase.recordNonFatal(throwable, metadata)
            }

            val message = if (remote?.reason == RemotePdfException.Reason.CIRCUIT_OPEN) {
                val diagnostics = remote.diagnostics
                if (diagnostics != null) {
                    val detail = buildCircuitDiagnosticsDetail(diagnostics)
                    app.getString(
                        R.string.error_remote_open_disabled_with_diagnostics,
                        diagnostics.failureCount,
                        detail,
                    )
                } else {
                    app.getString(R.string.error_remote_open_disabled)
                }
            } else {
                val messageRes = resolveErrorMessageRes(
                    throwable = throwable,
                    pdfFallback = R.string.error_remote_open_failed,
                )
                app.getString(messageRes)
            }
            showError(message)
        }
    }

    private fun buildCircuitDiagnosticsDetail(diagnostics: RemoteSourceDiagnostics): String {
        val reasonLabel = diagnostics.lastFailureReason?.let(::remoteReasonLabel)
        val message = diagnostics.lastFailureMessage
            ?.let(::sanitizeDiagnosticsMessage)
            ?.takeIf { it.isNotBlank() }
        return when {
            reasonLabel != null && message != null -> "$reasonLabel: $message"
            reasonLabel != null -> reasonLabel
            message != null -> message
            else -> app.getString(R.string.error_remote_open_diagnostics_unknown)
        }
    }

    private fun remoteReasonLabel(reason: RemotePdfException.Reason): String {
        val resId = when (reason) {
            RemotePdfException.Reason.NETWORK -> R.string.remote_failure_reason_network
            RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED -> R.string.remote_failure_reason_network_retry
            RemotePdfException.Reason.CORRUPTED -> R.string.remote_failure_reason_corrupted
            RemotePdfException.Reason.CIRCUIT_OPEN -> R.string.remote_failure_reason_circuit_open
            RemotePdfException.Reason.UNSAFE -> R.string.remote_failure_reason_unsafe
            RemotePdfException.Reason.FILE_TOO_LARGE -> R.string.remote_failure_reason_large
        }
        return app.getString(resId)
    }

    private fun sanitizeDiagnosticsMessage(message: String): String {
        return message.replace('\n', ' ').replace("\r", " ").trim()
    }

    private fun showError(message: String) {
        updateUiState { current ->
            current.copy(
                documentStatus = DocumentStatus.Error(message)
            )
        }
    }

    fun dismissError() {
        viewModelScope.launch {
            updateUiState { current ->
                if (current.documentStatus is DocumentStatus.Error) {
                    current.copy(documentStatus = DocumentStatus.Idle)
                } else {
                    current
                }
            }
        }
    }

    fun onPageFocused(index: Int) {
        val session = documentUseCase.session.value ?: return
        if (index !in 0 until session.pageCount) return
        adaptiveFlowUseCase.trackPageChange(index, session.pageCount)
        _uiState.value = _uiState.value.copy(currentPage = index)
        val preloadTargets = adaptiveFlowUseCase.preloadTargets.value
        if (preloadTargets.isNotEmpty() && !shouldThrottlePrefetch()) {
            val width = viewportWidthPx
            if (width > 0) {
                val targets = preloadTargets.filterNot(::isPageMalformed)
                if (targets.isNotEmpty()) {
                    prefetchRequests.trySend(PrefetchRequest(targets, width)).isSuccess
                }
            }
        }
    }

    fun onPageSettled(index: Int, zoom: Float = 1f) {
        val session = documentUseCase.session.value ?: return
        if (index !in 0 until session.pageCount) return
        val documentId = session.documentId
        pendingViewportCommit = ViewportCommit(documentId = documentId, pageIndex = index, zoom = zoom)
        viewportCommitJob?.cancel()
        viewportCommitJob = viewModelScope.launch {
            delay(VIEWPORT_PERSIST_THROTTLE_MS)
            val pending = pendingViewportCommit ?: return@launch
            if (pending.documentId != documentUseCase.session.value?.documentId) {
                return@launch
            }
            runCatching { preferencesUseCase.setLastDocumentViewport(pending.pageIndex, pending.zoom) }
        }
    }

    suspend fun renderPage(
        index: Int,
        targetWidth: Int,
        priority: RenderWorkQueue.Priority,
    ): Bitmap? {
        if (isPageMalformed(index)) {
            return null
        }
        val documentId = activeDocumentId ?: _uiState.value.documentId ?: return null
        val profile = renderProfileFor(priority)
        val cacheKey = PageCacheKey(documentId, index, targetWidth, profile)
        getCachedPage(cacheKey)?.let { return it }
        return renderQueue.submit(priority) {
            getCachedPage(cacheKey)?.let { return@submit it }
            if (isPageMalformed(index)) {
                return@submit null
            }
            val signal = CancellationSignal()
            val result = renderPageUseCase(RenderPageRequest(index, targetWidth, profile), signal)
            result.getOrNull()?.bitmap?.let { bitmap ->
                cachePageBitmap(documentId, cacheKey, bitmap)
                return@submit bitmap
            }
            val throwable = result.exceptionOrNull()
            if (throwable is CancellationException) throw throwable
            if (throwable is PdfRenderException) {
                when (throwable.reason) {
                    PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                        notifyPageTooLarge()
                        val fallbackWidth = throwable.suggestedWidth?.takeIf { suggestion ->
                            suggestion in 1 until targetWidth
                        }
                        if (fallbackWidth != null) {
                            val fallbackKey = cacheKey.copy(widthPx = fallbackWidth)
                            getCachedPage(fallbackKey)?.let { return@submit it }
                            val fallbackSignal = CancellationSignal()
                            val fallbackResult = renderPageUseCase(
                                RenderPageRequest(index, fallbackWidth, profile),
                                fallbackSignal
                            )
                            fallbackResult.exceptionOrNull()?.let { error ->
                                if (error is CancellationException) throw error
                            }
                            val fallbackBitmap = fallbackResult.getOrNull()?.bitmap
                            if (fallbackBitmap != null) {
                                cachePageBitmap(documentId, fallbackKey, fallbackBitmap)
                            }
                            return@submit fallbackBitmap
                        }
                    }

                    PdfRenderException.Reason.MALFORMED_PAGE -> {
                        markPageMalformed(index)
                    }
                }
            }
            if (throwable is DomainException && throwable.code == DomainErrorCode.IO_TIMEOUT) {
                notifyOperationTimeout()
            }
            null
        }
    }

    private fun renderProfileFor(priority: RenderWorkQueue.Priority): PageRenderProfile = when (priority) {
        RenderWorkQueue.Priority.THUMBNAIL -> thumbnailRenderProfile
        RenderWorkQueue.Priority.NEARBY_PAGE,
        RenderWorkQueue.Priority.VISIBLE_PAGE -> PageRenderProfile.HIGH_DETAIL
    }

    private fun resolveThumbnailRenderProfile(): PageRenderProfile {
        // Hook for device-specific overrides. Defaults to memory-saving RGB_565 rendering.
        return PageRenderProfile.LOW_DETAIL
    }

    suspend fun renderTile(index: Int, rect: Rect, scale: Float): Bitmap? {
        if (isPageMalformed(index)) {
            return null
        }
        val documentId = activeDocumentId ?: _uiState.value.documentId ?: return null
        val cacheKey = TileCacheKey(
            documentId = documentId,
            pageIndex = index,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            scaleBits = scale.toBits(),
        )
        getCachedTile(cacheKey)?.let { return it }
        return withContext(renderDispatcher) {
            getCachedTile(cacheKey)?.let { return@withContext it }
            if (isPageMalformed(index)) {
                return@withContext null
            }
            val signal = CancellationSignal()
            val result = renderTileUseCase(RenderTileRequest(index, rect, scale), signal)
            result.getOrNull()?.bitmap?.let { bitmap ->
                cacheTileBitmap(documentId, cacheKey, bitmap)
                return@withContext bitmap
            }
            val throwable = result.exceptionOrNull()
            if (throwable is CancellationException) throw throwable
            if (throwable is PdfRenderException) {
                when (throwable.reason) {
                    PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                        notifyPageTooLarge()
                        val fallbackScale = throwable.suggestedScale?.takeIf { suggestion ->
                            suggestion in 0f..scale && suggestion < scale
                        }
                        if (fallbackScale != null) {
                            val fallbackKey = cacheKey.copy(scaleBits = fallbackScale.toBits())
                            getCachedTile(fallbackKey)?.let { return@withContext it }
                            val fallbackSignal = CancellationSignal()
                            val fallbackResult = renderTileUseCase(
                                RenderTileRequest(index, rect, fallbackScale),
                                fallbackSignal
                            )
                            fallbackResult.exceptionOrNull()?.let { error ->
                                if (error is CancellationException) throw error
                            }
                            val fallbackBitmap = fallbackResult.getOrNull()?.bitmap
                            if (fallbackBitmap != null) {
                                cacheTileBitmap(documentId, fallbackKey, fallbackBitmap)
                            }
                            return@withContext fallbackBitmap
                        }
                    }

                    PdfRenderException.Reason.MALFORMED_PAGE -> {
                        markPageMalformed(index)
                    }
                }
            }
            if (throwable is DomainException && throwable.code == DomainErrorCode.IO_TIMEOUT) {
                notifyOperationTimeout()
            }
            null
        }
    }

    suspend fun pageSize(index: Int): Size? {
        return withContext(dispatchers.io) {
            documentUseCase.getPageSize(index)
        }
    }

    fun jumpToPage(index: Int) {
        val session = documentUseCase.session.value ?: return
        if (index !in 0 until session.pageCount) return
        _uiState.value = _uiState.value.copy(currentPage = index)
        onPageSettled(index)
    }

    fun updateViewportWidth(widthPx: Int) {
        if (widthPx <= 0) return
        viewportWidthPx = widthPx
        val preloadTargets = adaptiveFlowUseCase.preloadTargets.value
        if (preloadTargets.isNotEmpty() && !shouldThrottlePrefetch()) {
            val targets = preloadTargets.filterNot(::isPageMalformed)
            if (targets.isNotEmpty()) {
                prefetchRequests.trySend(PrefetchRequest(targets, viewportWidthPx)).isSuccess
            }
        }
    }

    fun prefetchPages(indices: List<Int>, widthPx: Int) {
        if (indices.isEmpty() || widthPx <= 0 || shouldThrottlePrefetch()) return
        val targets = indices.filterNot(::isPageMalformed)
        if (targets.isEmpty()) return
        prefetchRequests.trySend(PrefetchRequest(targets, widthPx)).isSuccess
    }

    fun exportDocument(context: android.content.Context): Boolean {
        val session = documentUseCase.session.value ?: return false
        val printManager = context.getSystemService(PrintManager::class.java) ?: return false
        val adapter = documentUseCase.createPrintAdapter(context) ?: return false
        val jobName = session.documentId.substringAfterLast('/')
            .ifEmpty { "NovaPDF document" }
        val attributes = PrintAttributes.Builder().build()
        printManager.print(jobName, adapter, attributes)
        return true
    }

    fun addAnnotation(annotation: AnnotationCommand) {
        val documentId = _uiState.value.documentId ?: return
        annotationUseCase.addAnnotation(documentId, annotation)
        _uiState.value = _uiState.value.copy(
            activeAnnotations = annotationUseCase.annotationsFor(documentId)
        )
        maintenanceUseCase.scheduleAutosave(documentId)
    }

    fun toggleBookmark(pageIndex: Int = _uiState.value.currentPage) {
        val documentId = _uiState.value.documentId ?: return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        val targetPage = pageIndex.coerceIn(0, pageCount - 1)
        viewModelScope.launch(dispatchers.io) {
            bookmarkUseCase.toggle(documentId, targetPage)
            val updatedBookmarks = bookmarkUseCase.bookmarksFor(documentId)
            withContext(dispatchers.main) {
                _uiState.value = _uiState.value.copy(bookmarks = updatedBookmarks)
                maintenanceUseCase.scheduleAutosave(documentId)
            }
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            dynamicColorEnabled = enabled,
            highContrastEnabled = if (enabled) _uiState.value.highContrastEnabled else false
        )
    }

    fun setHighContrastEnabled(enabled: Boolean) {
        if (!_uiState.value.dynamicColorEnabled) {
            _uiState.value = _uiState.value.copy(highContrastEnabled = false)
        } else {
            _uiState.value = _uiState.value.copy(highContrastEnabled = enabled)
        }
    }

    fun setTalkBackIntegrationEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(talkBackIntegrationEnabled = enabled)
    }

    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(0.8f, 2f)
        _uiState.value = _uiState.value.copy(fontScale = clamped)
    }

    fun setDevDiagnosticsEnabled(enabled: Boolean) {
        if (_uiState.value.devDiagnosticsEnabled == enabled) return
        NovaLog.i(TAG, "Developer diagnostics toggled", field("enabled", enabled))
        _uiState.update { current -> current.copy(devDiagnosticsEnabled = enabled) }
    }

    fun setDevCachesEnabled(enabled: Boolean) {
        val previous = renderCachesEnabled.getAndSet(enabled)
        if (previous != enabled) {
            NovaLog.i(TAG, "Developer caches toggled", field("enabled", enabled))
            if (!enabled) {
                clearRenderCaches()
            }
            recomputePrefetchEnabled(documentUseCase.session.value)
        }
        if (_uiState.value.devCachesEnabled != enabled) {
            _uiState.update { current -> current.copy(devCachesEnabled = enabled) }
        }
    }

    fun setDevArtificialDelayEnabled(enabled: Boolean) {
        if (!BuildConfig.DEBUG && enabled) {
            NovaLog.w(TAG, "Artificial delay unavailable in non-debug build")
            if (_uiState.value.devArtificialDelayEnabled) {
                _uiState.update { it.copy(devArtificialDelayEnabled = false) }
            }
            return
        }
        val changed = devArtificialDelayEnabled != enabled
        devArtificialDelayEnabled = enabled
        artificialDelayHandler.removeCallbacks(artificialDelayRunnable)
        if (enabled) {
            NovaLog.w(TAG, "Artificial ANR reproduction enabled")
            artificialDelayHandler.post(artificialDelayRunnable)
        } else if (changed) {
            NovaLog.i(TAG, "Artificial ANR reproduction disabled")
        }
        if (_uiState.value.devArtificialDelayEnabled != enabled) {
            _uiState.update { current -> current.copy(devArtificialDelayEnabled = enabled) }
        }
    }

    fun persistAnnotations() {
        val documentId = _uiState.value.documentId ?: return
        maintenanceUseCase.requestImmediateSync(documentId)
        enqueueMessage(R.string.annotations_sync_scheduled)
    }

    private fun enqueueMessage(@StringRes messageRes: Int) {
        _messageEvents.tryEmit(UiMessage(messageRes = messageRes))
    }

    private fun promptLargeRemoteDownload(source: DocumentSource, sizeBytes: Long, maxBytes: Long) {
        updateUiState { current ->
            current.copy(
                pendingLargeDownload = PendingLargeDownload(
                    source = source,
                    sizeBytes = sizeBytes,
                    maxBytes = maxBytes,
                )
            )
        }
    }

    private fun clearPendingLargeDownload() {
        updateUiState { current ->
            if (current.pendingLargeDownload == null) {
                current
            } else {
                current.copy(pendingLargeDownload = null)
            }
        }
    }

    private fun DocumentSource.withLargeFileConsentFlag(): DocumentSource {
        return when (this) {
            is DocumentSource.RemoteUrl -> this.withLargeFileConsent()
            is DocumentSource.GoogleDrive -> this.withLargeFileConsent()
            is DocumentSource.Dropbox -> this.withLargeFileConsent()
        }
    }

    private fun isPageMalformed(pageIndex: Int): Boolean {
        return pageIndex in _uiState.value.malformedPages
    }

    private fun markPageMalformed(pageIndex: Int) {
        updateUiState { current ->
            if (pageIndex in current.malformedPages) {
                current
            } else {
                current.copy(malformedPages = current.malformedPages + pageIndex)
            }
        }
    }

    private fun notifyPageTooLarge() {
        if (pageTooLargeNotified.compareAndSet(false, true)) {
            enqueueMessage(R.string.error_page_too_large)
        }
    }

    private fun notifyOperationTimeout() {
        enqueueMessage(R.string.error_document_io_issue)
    }

    @StringRes
    private fun resolveErrorMessageRes(
        throwable: Throwable,
        @StringRes pdfFallback: Int,
    ): Int {
        val pdfOpen = throwable.findCause<PdfOpenException>()
        if (pdfOpen != null) {
            return when (pdfOpen.reason) {
                PdfOpenException.Reason.CORRUPTED -> R.string.error_pdf_corrupted
                PdfOpenException.Reason.UNSUPPORTED -> R.string.error_pdf_unsupported
                PdfOpenException.Reason.ACCESS_DENIED -> R.string.error_pdf_permission
            }
        }

        val remote = throwable.findCause<RemotePdfException>()
        if (remote != null) {
            return when (remote.reason) {
                RemotePdfException.Reason.CORRUPTED -> R.string.error_pdf_corrupted
                RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED ->
                    R.string.error_remote_open_failed_after_retries
                RemotePdfException.Reason.NETWORK -> R.string.error_remote_open_failed
                RemotePdfException.Reason.CIRCUIT_OPEN -> R.string.error_remote_open_disabled
                RemotePdfException.Reason.UNSAFE -> R.string.error_remote_pdf_unsafe
                RemotePdfException.Reason.FILE_TOO_LARGE -> R.string.error_remote_pdf_too_large
            }
        }

        val domainError = throwable.findCause<DomainException>()?.code
        if (domainError != null) {
            return messageForDomainError(domainError)
        }

        return pdfFallback
    }

    @StringRes
    private fun messageForDomainError(code: DomainErrorCode): Int {
        return when (code) {
            DomainErrorCode.IO_TIMEOUT -> R.string.error_document_io_issue
            DomainErrorCode.PDF_MALFORMED -> R.string.error_pdf_corrupted
            DomainErrorCode.RENDER_OOM -> R.string.error_document_render_limit
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch(indexDispatcher) {
            val session = documentUseCase.session.value ?: return@launch
            val results = runCatching { searchUseCase.search(session, query) }
                .onFailure { throwable -> NovaLog.e("PdfViewerViewModel", "Search failed", throwable) }
                .getOrDefault(emptyList())
            updateUiState { it.copy(searchResults = results) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adaptiveFlowUseCase.stop()
        documentUseCase.dispose()
        indexingJob?.cancel()
        remoteDownloadJob?.cancel()
        prefetchRequests.close()
        processLifecycleOwner?.lifecycle?.removeObserver(appLifecycleObserver)
        app.unregisterComponentCallbacks(memoryCallbacks)
        devArtificialDelayEnabled = false
        artificialDelayHandler.removeCallbacks(artificialDelayRunnable)
        clearRenderCaches()
    }

    private fun isNightModeEnabled(): Boolean {
        val configuration = getApplication<Application>().resources.configuration
        val mask = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

    private fun bitmapSize(bitmap: Bitmap): Int {
        val allocation = try {
            bitmap.allocationByteCount
        } catch (_: IllegalStateException) {
            try {
                bitmap.byteCount
            } catch (_: IllegalStateException) {
                0
            }
        }
        return allocation.coerceAtLeast(1)
    }

    private fun getCachedPage(key: PageCacheKey): Bitmap? {
        if (!renderCachesEnabled.get()) return null
        return synchronized(pageCacheLock) {
            val bitmap = pageBitmapCache.get(key)
            if (bitmap == null || bitmap.isRecycled) {
                if (bitmap != null && bitmap.isRecycled) {
                    pageBitmapCache.remove(key)
                }
                null
            } else {
                bitmap
            }
        }
    }

    private fun cachePageBitmap(documentId: String, key: PageCacheKey, bitmap: Bitmap) {
        if (!renderCachesEnabled.get()) return
        if (!bitmap.isRecycled && documentId == activeDocumentId) {
            synchronized(pageCacheLock) {
                pageBitmapCache.put(key, bitmap)
            }
        }
    }

    private fun getCachedTile(key: TileCacheKey): Bitmap? {
        if (!renderCachesEnabled.get()) return null
        return synchronized(tileCacheLock) {
            val bitmap = tileBitmapCache.get(key)
            if (bitmap == null || bitmap.isRecycled) {
                if (bitmap != null && bitmap.isRecycled) {
                    tileBitmapCache.remove(key)
                }
                null
            } else {
                bitmap
            }
        }
    }

    private fun cacheTileBitmap(documentId: String, key: TileCacheKey, bitmap: Bitmap) {
        if (!renderCachesEnabled.get()) return
        if (!bitmap.isRecycled && documentId == activeDocumentId) {
            synchronized(tileCacheLock) {
                tileBitmapCache.put(key, bitmap)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun dumpRuntimeDiagnostics() {
        viewModelScope.launch(dispatchers.io) {
            val state = uiState.value
            val renderStats = renderQueue.stats.value
            val pageCacheStats = captureCacheStats(pageCacheLock, pageBitmapCache)
            val tileCacheStats = captureCacheStats(tileCacheLock, tileBitmapCache)
            val viewportCommit = pendingViewportCommit
            val viewportJobActive = viewportCommitJob?.isActive == true

            NovaLog.i(
                TAG,
                "Developer runtime dump",
                field("documentId", state.documentId),
                field("pageCount", state.pageCount),
                field("currentPage", state.currentPage),
                field("uiUnderLoad", state.uiUnderLoad),
                field("searchIndexing", state.searchIndexing::class.simpleName),
                field("renderProgress", state.renderProgress::class.simpleName)
            )

            NovaLog.i(
                TAG,
                "Render queue snapshot",
                field("parallelism", RENDER_POOL_PARALLELISM),
                field("active", renderStats.active),
                field("visibleQueued", renderStats.visible),
                field("nearbyQueued", renderStats.nearby),
                field("thumbnailQueued", renderStats.thumbnail)
            )

            logCacheStats("page_bitmap", pageCacheStats)
            logCacheStats("tile_bitmap", tileCacheStats)

            NovaLog.i(
                TAG,
                "Bitmap memory stats",
                field("currentBytes", state.bitmapMemory.currentBytes),
                field("peakBytes", state.bitmapMemory.peakBytes),
                field("warnThreshold", state.bitmapMemory.warnThresholdBytes),
                field("criticalThreshold", state.bitmapMemory.criticalThresholdBytes),
                field("level", state.bitmapMemory.level.name)
            )

            NovaLog.i(
                TAG,
                "Prefetch channel",
                field("isClosedForSend", prefetchRequests.isClosedForSend),
                field("isClosedForReceive", prefetchRequests.isClosedForReceive),
                field("isEmpty", prefetchRequests.isEmpty)
            )

            NovaLog.i(
                TAG,
                "Viewport commit",
                field("hasPending", viewportCommit != null),
                field("jobActive", viewportJobActive),
                field("throttleMs", VIEWPORT_PERSIST_THROTTLE_MS)
            )
            viewportCommit?.let { commit ->
                NovaLog.i(
                    TAG,
                    "Pending viewport commit detail",
                    field("documentId", commit.documentId),
                    field("pageIndex", commit.pageIndex),
                    field("zoom", commit.zoom)
                )
            }

            val documentStatus = state.documentStatus
            when (documentStatus) {
                is DocumentStatus.Error -> NovaLog.i(
                    TAG,
                    "Document status: error",
                    field("message", documentStatus.message)
                )

                is DocumentStatus.Loading -> NovaLog.i(
                    TAG,
                    "Document status: loading",
                    field("progress", documentStatus.progress),
                    field("messageRes", documentStatus.messageRes)
                )

                DocumentStatus.Idle -> NovaLog.i(TAG, "Document status: idle")
            }

            val threadStacks = Thread.getAllStackTraces()
            NovaLog.i(TAG, "Thread dump start", field("threadCount", threadStacks.size))
            threadStacks.entries
                .sortedBy { (thread, _) -> thread.name }
                .forEach { (thread, stack) ->
                    val stackTrace = if (stack.isEmpty()) {
                        "    <no stack>"
                    } else {
                        stack.joinToString(separator = "\n") { element -> "    at $element" }
                    }
                    val threadMessage = buildString {
                        append("Thread \"")
                        append(thread.name)
                        append("\" (id=")
                        append(thread.id)
                        append(", state=")
                        append(thread.state)
                        if (thread.isDaemon) {
                            append(", daemon")
                        }
                        append(")\n")
                        append(stackTrace)
                    }
                    NovaLog.i(TAG, threadMessage)
                }
            NovaLog.i(TAG, "Thread dump end")
        }
    }

    private fun clearRenderCaches() {
        synchronized(pageCacheLock) {
            pageBitmapCache.evictAll()
        }
        synchronized(tileCacheLock) {
            tileBitmapCache.evictAll()
        }
    }

    private fun stallMainThread(durationMs: Long) {
        val start = SystemClock.uptimeMillis()
        while (devArtificialDelayEnabled) {
            val elapsed = SystemClock.uptimeMillis() - start
            if (elapsed >= durationMs) {
                break
            }
            val remaining = durationMs - elapsed
            val sleepChunk = min(DEV_ANR_SLEEP_CHUNK_MS, remaining)
            try {
                Thread.sleep(sleepChunk)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun trimRenderCaches(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        synchronized(pageCacheLock) {
            pageBitmapCache.trimToFraction(clamped)
        }
        synchronized(tileCacheLock) {
            tileBitmapCache.trimToFraction(clamped)
        }
    }

    private fun <K, V> captureCacheStats(lock: Any, cache: LruCache<K, V>): CacheStats = synchronized(lock) {
        CacheStats(
            sizeBytes = cache.size(),
            maxSizeBytes = cache.maxSize(),
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            putCount = cache.putCount(),
            evictionCount = cache.evictionCount(),
        )
    }

    private fun logCacheStats(label: String, stats: CacheStats) {
        NovaLog.i(
            TAG,
            "$label cache",
            field("sizeBytes", stats.sizeBytes),
            field("maxBytes", stats.maxSizeBytes),
            field("hits", stats.hitCount),
            field("misses", stats.missCount),
            field("puts", stats.putCount),
            field("evictions", stats.evictionCount)
        )
    }

    private fun computeCacheBudget(limitBytes: Int): Int {
        val runtimeBudget = Runtime.getRuntime().maxMemory() / 8L
        val capped = minOf(limitBytes.toLong(), runtimeBudget)
        return capped.coerceAtLeast(MIN_CACHE_BYTES.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

}
