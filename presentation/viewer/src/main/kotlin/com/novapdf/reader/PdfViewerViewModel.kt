package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.util.Size
import android.os.Build
import android.os.CancellationSignal
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.annotation.StringRes
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfOpenException
import com.novapdf.reader.data.PdfRenderException
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.domain.usecase.BuildSearchIndexRequest
import com.novapdf.reader.domain.usecase.OpenDocumentRequest
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RenderPageRequest
import com.novapdf.reader.domain.usecase.RenderTileRequest
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.presentation.viewer.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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

private const val DEFAULT_THEME_SEED_COLOR = 0xFFD32F2FL
const val LARGE_DOCUMENT_PAGE_THRESHOLD = 400
private const val RENDER_POOL_PARALLELISM = 2
private const val INDEX_POOL_PARALLELISM = 1
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

data class PdfViewerUiState(
    val documentId: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val documentStatus: DocumentStatus = DocumentStatus.Idle,
    val isNightMode: Boolean = false,
    val readingSpeed: Float = 0f,
    val swipeSensitivity: Float = 1f,
    val searchResults: List<SearchResult> = emptyList(),
    val activeAnnotations: List<AnnotationCommand> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    val dynamicColorEnabled: Boolean = true,
    val highContrastEnabled: Boolean = false,
    val talkBackIntegrationEnabled: Boolean = false,
    val fontScale: Float = 1f,
    val themeSeedColor: Long = DEFAULT_THEME_SEED_COLOR,
    val outline: List<PdfOutlineNode> = emptyList(),
    val renderProgress: PdfRenderProgress = PdfRenderProgress.Idle,
    val bitmapMemory: BitmapMemoryStats = BitmapMemoryStats(),
    val malformedPages: Set<Int> = emptySet()
)

private data class DocumentContext(
    val speed: Float,
    val sensitivity: Float,
    val session: PdfDocumentSession?,
    val outline: List<PdfOutlineNode>,
    val renderProgress: PdfRenderProgress,
)

private data class PrefetchRequest(
    val indices: List<Int>,
    val widthPx: Int
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderDispatcher = dispatchers.io.limitedParallelism(RENDER_POOL_PARALLELISM)

    private val renderQueue = RenderWorkQueue(viewModelScope, renderDispatcher, RENDER_POOL_PARALLELISM)

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
        val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        _uiState.value = _uiState.value.copy(
            isNightMode = isNightModeEnabled(),
            dynamicColorEnabled = supportsDynamicColor
        )
        viewModelScope.launch {
            combine(
                adaptiveFlowUseCase.readingSpeed,
                adaptiveFlowUseCase.swipeSensitivity,
                documentUseCase.session,
                documentUseCase.outline,
                documentUseCase.renderProgress,
            ) { speed, sensitivity, session, outline, renderProgress ->
                DocumentContext(speed, sensitivity, session, outline, renderProgress)
            }.combine(documentUseCase.bitmapMemory) { context, bitmapMemory ->
                context to bitmapMemory
            }.collect { (context, bitmapMemory) ->
                val session = context.session
                prefetchEnabled = session?.pageCount?.let { it <= LARGE_DOCUMENT_PAGE_THRESHOLD } ?: true
                val annotations = session?.let { annotationUseCase.annotationsFor(it.documentId) }
                    .orEmpty()
                val bookmarks = if (session != null) {
                    bookmarkUseCase.bookmarksFor(session.documentId)
                } else {
                    emptyList()
                }
                val previous = _uiState.value
                val documentId = session?.documentId
                val shouldResetMalformed = previous.documentId != documentId
                _uiState.value = previous.copy(
                    readingSpeed = context.speed,
                    swipeSensitivity = context.sensitivity,
                    documentId = documentId,
                    pageCount = session?.pageCount ?: 0,
                    activeAnnotations = annotations,
                    bookmarks = bookmarks,
                    outline = context.outline,
                    renderProgress = context.renderProgress,
                    bitmapMemory = bitmapMemory,
                    malformedPages = if (shouldResetMalformed) emptySet() else previous.malformedPages
                )
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
    }

    private fun shouldThrottlePrefetch(): Boolean {
        if (!prefetchEnabled) return true
        return adaptiveFlowUseCase.isUiUnderLoad()
    }

    fun openDocument(uri: Uri) {
        viewModelScope.launch(dispatchers.io) {
            loadDocument(uri, resetError = true)
        }
    }

    fun openRemoteDocument(url: String) {
        val previousJob = remoteDownloadJob
        val newJob = viewModelScope.launch(dispatchers.io) {
            previousJob?.cancelAndJoin()
            setLoadingState(
                isLoading = true,
                progress = 0f,
                messageRes = R.string.loading_stage_downloading,
                resetError = true
            )
            val result = try {
                remoteDocumentUseCase.download(url)
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
                reportRemoteOpenFailure(throwable, url)
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
                reportRemoteOpenFailure(throwable, url)
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

    private suspend fun loadDocument(uri: Uri, resetError: Boolean) {
        searchJob?.cancel()
        pageTooLargeNotified.set(false)
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
        val message = when (throwable) {
            is PdfOpenException -> when (throwable.reason) {
                PdfOpenException.Reason.CORRUPTED -> app.getString(R.string.error_pdf_corrupted)
                PdfOpenException.Reason.UNSUPPORTED -> app.getString(R.string.error_pdf_unsupported)
                PdfOpenException.Reason.ACCESS_DENIED -> app.getString(R.string.error_pdf_permission)
            }
            else -> app.getString(R.string.error_document_open_generic)
        }
        showError(message)
    }

    fun reportRemoteOpenFailure(throwable: Throwable, sourceUrl: String? = null) {
        viewModelScope.launch {
            resetTransientStatus()
            if (throwable !is CancellationException) {
                val metadata = buildMap {
                    put("stage", "remoteDownload")
                    sourceUrl?.let { put("url", it) }
                    if (throwable is RemotePdfException) {
                        put("reason", throwable.reason.name)
                    }
                }
                crashReportingUseCase.recordNonFatal(throwable, metadata)
            }
            val messageRes = when (throwable) {
                is RemotePdfException -> when (throwable.reason) {
                    RemotePdfException.Reason.CORRUPTED -> R.string.error_pdf_corrupted
                    RemotePdfException.Reason.NETWORK -> R.string.error_remote_open_failed
                }
                else -> R.string.error_remote_open_failed
            }
            showError(app.getString(messageRes))
        }
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

    suspend fun renderPage(
        index: Int,
        targetWidth: Int,
        priority: RenderWorkQueue.Priority,
    ): Bitmap? {
        if (isPageMalformed(index)) {
            return null
        }
        return renderQueue.submit(priority) {
            if (isPageMalformed(index)) {
                return@submit null
            }
            val signal = CancellationSignal()
            val profile = renderProfileFor(priority)
            val result = renderPageUseCase(RenderPageRequest(index, targetWidth, profile), signal)
            result.getOrNull()?.bitmap ?: run {
                val throwable = result.exceptionOrNull()
                if (throwable is CancellationException) throw throwable
                if (throwable is PdfRenderException) {
                    return@run when (throwable.reason) {
                        PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                            notifyPageTooLarge()
                            val fallbackWidth = throwable.suggestedWidth?.takeIf { suggestion ->
                                suggestion in 1 until targetWidth
                            }
                            if (fallbackWidth != null) {
                                val fallbackSignal = CancellationSignal()
                                val fallbackResult = renderPageUseCase(
                                    RenderPageRequest(index, fallbackWidth, profile),
                                    fallbackSignal
                                )
                                fallbackResult.exceptionOrNull()?.let { error ->
                                    if (error is CancellationException) throw error
                                }
                                fallbackResult.getOrNull()?.bitmap
                            } else {
                                null
                            }
                        }

                        PdfRenderException.Reason.MALFORMED_PAGE -> {
                            markPageMalformed(index)
                            null
                        }
                    }
                }
                null
            }
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
        return withContext(renderDispatcher) {
            if (isPageMalformed(index)) {
                return@withContext null
            }
            val signal = CancellationSignal()
            val result = renderTileUseCase(RenderTileRequest(index, rect, scale), signal)
            result.getOrNull()?.bitmap ?: run {
                val throwable = result.exceptionOrNull()
                if (throwable is CancellationException) throw throwable
                if (throwable is PdfRenderException) {
                    return@run when (throwable.reason) {
                        PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                            notifyPageTooLarge()
                            val fallbackScale = throwable.suggestedScale?.takeIf { suggestion ->
                                suggestion in 0f..scale && suggestion < scale
                            }
                            if (fallbackScale != null) {
                                val fallbackSignal = CancellationSignal()
                                val fallbackResult = renderTileUseCase(
                                    RenderTileRequest(index, rect, fallbackScale),
                                    fallbackSignal
                                )
                                fallbackResult.exceptionOrNull()?.let { error ->
                                    if (error is CancellationException) throw error
                                }
                                fallbackResult.getOrNull()?.bitmap
                            } else {
                                null
                            }
                        }

                        PdfRenderException.Reason.MALFORMED_PAGE -> {
                            markPageMalformed(index)
                            null
                        }
                    }
                }
                null
            }
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

    fun persistAnnotations() {
        val documentId = _uiState.value.documentId ?: return
        maintenanceUseCase.requestImmediateSync(documentId)
        enqueueMessage(R.string.annotations_sync_scheduled)
    }

    private fun enqueueMessage(@StringRes messageRes: Int) {
        _messageEvents.tryEmit(UiMessage(messageRes = messageRes))
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

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch(indexDispatcher) {
            val session = documentUseCase.session.value ?: return@launch
            val results = runCatching { searchUseCase.search(session, query) }
                .onFailure { throwable -> Log.e("PdfViewerViewModel", "Search failed", throwable) }
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
    }

    private fun isNightModeEnabled(): Boolean {
        val configuration = getApplication<Application>().resources.configuration
        val mask = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

}
