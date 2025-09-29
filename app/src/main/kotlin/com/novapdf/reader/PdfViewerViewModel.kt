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
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.data.remote.PdfDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.novapdf.reader.work.DocumentMaintenanceScheduler

private const val DEFAULT_THEME_SEED_COLOR = 0xFFD32F2FL
data class PdfViewerUiState(
    val documentId: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val isLoading: Boolean = false,
    val loadingProgress: Float? = null,
    @StringRes val loadingMessageRes: Int? = null,
    val errorMessage: String? = null,
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
    val outline: List<PdfOutlineNode> = emptyList()
)

private data class DocumentContext(
    val speed: Float,
    val sensitivity: Float,
    val session: PdfDocumentSession?,
    val outline: List<PdfOutlineNode>
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

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var remoteDownloadJob: Job? = null
    private var viewportWidthPx: Int = 1080

    private suspend fun setLoadingState(
        isLoading: Boolean,
        progress: Float?,
        @StringRes messageRes: Int?,
        resetError: Boolean = false
    ) {
        updateUiState { current ->
            current.copy(
                isLoading = isLoading,
                loadingProgress = progress,
                loadingMessageRes = messageRes,
                errorMessage = if (resetError) null else current.errorMessage
            )
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
                pdfRepository.outline
            ) { speed, sensitivity, session, outline ->
                DocumentContext(speed, sensitivity, session, outline)
            }.collect { context ->
                val session = context.session
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
                    outline = context.outline
                )
            }
        }
        viewModelScope.launch {
            adaptiveFlowManager.preloadTargets.collect { targets ->
                if (targets.isNotEmpty()) {
                    val width = viewportWidthPx
                    if (width > 0) {
                        pdfRepository.prefetchPages(targets, width)
                    }
                }
            }
        }
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
                reportRemoteOpenFailure(throwable)
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
                reportRemoteOpenFailure(throwable)
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
        setLoadingState(
            isLoading = true,
            progress = 0f,
            messageRes = R.string.loading_stage_resolving,
            resetError = resetError
        )
        val session = runCatching { pdfRepository.open(uri) }
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
                isLoading = false,
                loadingProgress = null,
                loadingMessageRes = null,
                documentId = session.documentId,
                pageCount = session.pageCount,
                currentPage = 0,
                errorMessage = null,
                activeAnnotations = emptyList(),
                bookmarks = bookmarks,
                outline = pdfRepository.outline.value
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
        runCatching { pdfRepository.renderPage(0, targetWidth) }
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
        val message = when (throwable) {
            is PdfOpenException -> when (throwable.reason) {
                PdfOpenException.Reason.CORRUPTED -> app.getString(R.string.error_pdf_corrupted)
                PdfOpenException.Reason.UNSUPPORTED -> app.getString(R.string.error_pdf_unsupported)
                PdfOpenException.Reason.ACCESS_DENIED -> app.getString(R.string.error_pdf_permission)
            }
            else -> app.getString(R.string.error_document_open_generic)
        }
        updateUiState { current ->
            current.copy(
                isLoading = false,
                loadingProgress = null,
                loadingMessageRes = null,
                errorMessage = message
            )
        }
    }

    fun reportRemoteOpenFailure(@Suppress("UNUSED_PARAMETER") throwable: Throwable) {
        viewModelScope.launch {
            updateUiState { current ->
                current.copy(
                    isLoading = false,
                    loadingProgress = null,
                    loadingMessageRes = null,
                    errorMessage = app.getString(R.string.error_remote_open_failed)
                )
            }
        }
    }

    fun dismissError() {
        viewModelScope.launch {
            updateUiState { it.copy(errorMessage = null) }
        }
    }

    fun onPageFocused(index: Int) {
        val session = pdfRepository.session.value ?: return
        if (index !in 0 until session.pageCount) return
        adaptiveFlowManager.trackPageChange(index, session.pageCount)
        _uiState.value = _uiState.value.copy(currentPage = index)
        val preloadTargets = adaptiveFlowManager.preloadTargets.value
        if (preloadTargets.isNotEmpty()) {
            val width = viewportWidthPx
            if (width > 0) {
                pdfRepository.prefetchPages(preloadTargets, width)
            }
        }
    }

    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap? {
        return pdfRepository.renderPage(index, targetWidth)
    }

    suspend fun renderTile(index: Int, rect: Rect, scale: Float): Bitmap? {
        return pdfRepository.renderTile(index, rect, scale)
    }

    suspend fun pageSize(index: Int): Size? {
        return pdfRepository.getPageSize(index)
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
        if (preloadTargets.isNotEmpty()) {
            pdfRepository.prefetchPages(preloadTargets, viewportWidthPx)
        }
    }

    fun prefetchPages(indices: List<Int>, widthPx: Int) {
        if (indices.isEmpty() || widthPx <= 0) return
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
