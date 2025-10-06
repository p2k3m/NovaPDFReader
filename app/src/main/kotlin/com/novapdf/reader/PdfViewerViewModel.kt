package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.util.Size
import android.content.res.Configuration
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfOpenException
import com.novapdf.reader.R
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.data.remote.RemotePdfException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
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
import com.novapdf.reader.work.DocumentMaintenanceScheduler

private const val DEFAULT_THEME_SEED_COLOR = 0xFFD32F2FL
internal const val LARGE_DOCUMENT_PAGE_THRESHOLD = 400
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
    val renderProgress: PdfRenderProgress = PdfRenderProgress.Idle
)

private data class DocumentContext(
    val speed: Float,
    val sensitivity: Float,
    val session: PdfDocumentSession?,
    val outline: List<PdfOutlineNode>,
    val renderProgress: PdfRenderProgress
)

open class PdfViewerViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val app = application as NovaPdfApp
    private val annotationRepository: AnnotationRepository = app.annotationRepository
    private val pdfRepository: PdfDocumentRepository = app.pdfDocumentRepository
    private val adaptiveFlowManager: AdaptiveFlowManager = app.adaptiveFlowManager
    private val bookmarkManager: BookmarkManager = app.bookmarkManager
    private val documentMaintenanceScheduler: DocumentMaintenanceScheduler = app.documentMaintenanceScheduler
    private val searchCoordinator: LuceneSearchCoordinator = app.searchCoordinator
    private val downloadManager: PdfDownloadManager = app.pdfDownloadManager
    private val crashReporter: CrashReporter = app.crashReporter

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _messageEvents = MutableSharedFlow<UiMessage>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messageEvents: SharedFlow<UiMessage> = _messageEvents.asSharedFlow()

    private var searchJob: Job? = null
    private var remoteDownloadJob: Job? = null
    private var viewportWidthPx: Int = 1080
    private var prefetchEnabled: Boolean = true

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
                adaptiveFlowManager.readingSpeedPagesPerMinute,
                adaptiveFlowManager.swipeSensitivity,
                pdfRepository.session,
                pdfRepository.outline,
                pdfRepository.renderProgress
            ) { speed, sensitivity, session, outline, renderProgress ->
                DocumentContext(speed, sensitivity, session, outline, renderProgress)
            }.collect { context ->
                val session = context.session
                prefetchEnabled = session?.pageCount?.let { it <= LARGE_DOCUMENT_PAGE_THRESHOLD } ?: true
                val annotations = session?.let { annotationRepository.annotationsForDocument(it.documentId) }
                    .orEmpty()
                val bookmarks = session?.let { bookmarkManager.bookmarks(it.documentId) } ?: emptyList()
                _uiState.value = _uiState.value.copy(
                    readingSpeed = context.speed,
                    swipeSensitivity = context.sensitivity,
                    documentId = session?.documentId,
                    pageCount = session?.pageCount ?: 0,
                    activeAnnotations = annotations,
                    bookmarks = bookmarks,
                    outline = context.outline,
                    renderProgress = context.renderProgress
                )
            }
        }
        viewModelScope.launch {
            adaptiveFlowManager.preloadTargets.collect { targets ->
                if (targets.isNotEmpty() && !shouldThrottlePrefetch()) {
                    val width = viewportWidthPx
                    if (width > 0) {
                        pdfRepository.prefetchPages(targets, width)
                    }
                }
            }
        }
    }

    private fun shouldThrottlePrefetch(): Boolean {
        if (!prefetchEnabled) return true
        return adaptiveFlowManager.isUiUnderLoad()
    }

    fun openDocument(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            loadDocument(uri, resetError = true)
        }
    }

    fun openRemoteDocument(url: String) {
        val previousJob = remoteDownloadJob
        val newJob = viewModelScope.launch(Dispatchers.IO) {
            previousJob?.cancelAndJoin()
            setLoadingState(
                isLoading = true,
                progress = 0f,
                messageRes = R.string.loading_stage_downloading,
                resetError = true
            )
            val result = try {
                downloadManager.download(url)
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

    private suspend fun loadDocument(uri: Uri, resetError: Boolean) {
        searchJob?.cancel()
        setLoadingState(
            isLoading = true,
            progress = 0f,
            messageRes = R.string.loading_stage_resolving,
            resetError = resetError
        )
        val session = runCatching {
            withContext(Dispatchers.IO) { pdfRepository.open(uri) }
        }
            .getOrElse { throwable ->
                handleDocumentError(throwable)
                return
            }
        annotationRepository.clearInMemory(session.documentId)
        setLoadingState(
            isLoading = true,
            progress = 0.35f,
            messageRes = R.string.loading_stage_parsing
        )
        withContext(Dispatchers.Main) {
            adaptiveFlowManager.start()
        }
        adaptiveFlowManager.trackPageChange(0, session.pageCount)
        preloadInitialPage(session)
        setLoadingState(
            isLoading = true,
            progress = 0.85f,
            messageRes = R.string.loading_stage_finalizing
        )
        val bookmarks = bookmarkManager.bookmarks(session.documentId)
        updateUiState { current ->
            current.copy(
                documentStatus = DocumentStatus.Idle,
                documentId = session.documentId,
                pageCount = session.pageCount,
                currentPage = 0,
                activeAnnotations = emptyList(),
                bookmarks = bookmarks,
                outline = pdfRepository.outline.value,
                searchResults = emptyList()
            )
        }
        searchCoordinator.prepare(session)
    }

    private suspend fun preloadInitialPage(session: PdfDocumentSession) {
        setLoadingState(
            isLoading = true,
            progress = 0.55f,
            messageRes = R.string.loading_stage_rendering
        )
        val targetWidth = viewportWidthPx.coerceAtLeast(480)
        withContext(Dispatchers.IO) {
            runCatching { pdfRepository.renderPage(0, targetWidth) }
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
        crashReporter.recordNonFatal(throwable, metadata)
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
                crashReporter.recordNonFatal(throwable, metadata)
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
        val session = pdfRepository.session.value ?: return
        if (index !in 0 until session.pageCount) return
        adaptiveFlowManager.trackPageChange(index, session.pageCount)
        _uiState.value = _uiState.value.copy(currentPage = index)
        val preloadTargets = adaptiveFlowManager.preloadTargets.value
        if (preloadTargets.isNotEmpty() && !shouldThrottlePrefetch()) {
            val width = viewportWidthPx
            if (width > 0) {
                pdfRepository.prefetchPages(preloadTargets, width)
            }
        }
    }

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            pdfRepository.renderPage(index, targetWidth)
        }
    }

    suspend fun renderTile(index: Int, rect: Rect, scale: Float): Bitmap? {
        return withContext(Dispatchers.IO) {
            pdfRepository.renderTile(index, rect, scale)
        }
    }

    suspend fun pageSize(index: Int): Size? {
        return withContext(Dispatchers.IO) {
            pdfRepository.getPageSize(index)
        }
    }

    fun jumpToPage(index: Int) {
        val session = pdfRepository.session.value ?: return
        if (index !in 0 until session.pageCount) return
        _uiState.value = _uiState.value.copy(currentPage = index)
    }

    fun updateViewportWidth(widthPx: Int) {
        if (widthPx <= 0) return
        viewportWidthPx = widthPx
        val preloadTargets = adaptiveFlowManager.preloadTargets.value
        if (preloadTargets.isNotEmpty() && !shouldThrottlePrefetch()) {
            pdfRepository.prefetchPages(preloadTargets, viewportWidthPx)
        }
    }

    fun prefetchPages(indices: List<Int>, widthPx: Int) {
        if (indices.isEmpty() || widthPx <= 0 || shouldThrottlePrefetch()) return
        viewModelScope.launch(Dispatchers.IO) {
            pdfRepository.prefetchPages(indices, widthPx)
        }
    }

    fun exportDocument(context: android.content.Context): Boolean {
        val session = pdfRepository.session.value ?: return false
        val printManager = context.getSystemService(PrintManager::class.java) ?: return false
        val adapter = pdfRepository.createPrintAdapter(context) ?: return false
        val jobName = session.documentId.substringAfterLast('/')
            .ifEmpty { "NovaPDF document" }
        val attributes = PrintAttributes.Builder().build()
        printManager.print(jobName, adapter, attributes)
        return true
    }

    fun addAnnotation(annotation: AnnotationCommand) {
        val documentId = _uiState.value.documentId ?: return
        annotationRepository.addAnnotation(documentId, annotation)
        _uiState.value = _uiState.value.copy(
            activeAnnotations = annotationRepository.annotationsForDocument(documentId)
        )
        documentMaintenanceScheduler.scheduleAutosave(documentId)
    }

    fun toggleBookmark(pageIndex: Int = _uiState.value.currentPage) {
        val documentId = _uiState.value.documentId ?: return
        val pageCount = _uiState.value.pageCount
        if (pageCount <= 0) return
        val targetPage = pageIndex.coerceIn(0, pageCount - 1)
        viewModelScope.launch {
            bookmarkManager.toggleBookmark(documentId, targetPage)
            _uiState.value = _uiState.value.copy(
                bookmarks = bookmarkManager.bookmarks(documentId)
            )
            documentMaintenanceScheduler.scheduleAutosave(documentId)
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
        documentMaintenanceScheduler.requestImmediateSync(documentId)
        enqueueMessage(R.string.annotations_sync_scheduled)
    }

    private fun enqueueMessage(@StringRes messageRes: Int) {
        _messageEvents.tryEmit(UiMessage(messageRes = messageRes))
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val session = pdfRepository.session.value ?: return@launch
            val results = runCatching { searchCoordinator.search(session, query) }
                .onFailure { throwable -> Log.e("PdfViewerViewModel", "Search failed", throwable) }
                .getOrDefault(emptyList())
            updateUiState { it.copy(searchResults = results) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        adaptiveFlowManager.stop()
        pdfRepository.dispose()
        remoteDownloadJob?.cancel()
    }

    private fun isNightModeEnabled(): Boolean {
        val configuration = getApplication<Application>().resources.configuration
        val mask = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

}
