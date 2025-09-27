package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.content.res.Configuration
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.TextRunSnapshot
import com.novapdf.reader.search.collectMatchesFromRuns
import com.novapdf.reader.search.detectTextRegions
import com.novapdf.reader.search.normalizeSearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val errorMessage: String? = null,
    val isNightMode: Boolean = false,
    val readingSpeed: Float = 0f,
    val swipeSensitivity: Float = 1f,
    val searchResults: List<SearchResult> = emptyList(),
    val activeAnnotations: List<AnnotationCommand> = emptyList(),
    val bookmarks: List<Int> = emptyList(),
    val dynamicColorEnabled: Boolean = true,
    val highContrastEnabled: Boolean = false,
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

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var viewportWidthPx: Int = 1080

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
            updateUiState { current ->
                current.copy(isLoading = true, loadingProgress = 0f, errorMessage = null)
            }
            val openResult = runCatching { pdfRepository.open(uri) }
            val session = openResult.getOrNull()
            if (session == null) {
                val throwable = openResult.exceptionOrNull()
                if (throwable != null) {
                    handleDocumentError(throwable)
                } else {
                    updateUiState { current ->
                        current.copy(isLoading = false, loadingProgress = null, errorMessage = "Unable to open document")
                    }
                }
                return@launch
            }
            annotationRepository.clearInMemory(session.documentId)
            updateUiState { it.copy(loadingProgress = 0.35f) }
            lastTileSpec = null
            adaptiveFlowManager.start()
            adaptiveFlowManager.trackPageChange(0, session.pageCount)
            preloadInitialPage(session)
            val bookmarks = bookmarkManager.bookmarks(session.documentId)
            updateUiState { current ->
                current.copy(
                    isLoading = false,
                    loadingProgress = null,
                    documentId = session.documentId,
                    pageCount = session.pageCount,
                    currentPage = 0,
                    errorMessage = null,
                    activeAnnotations = emptyList(),
                    bookmarks = bookmarks,
                    outline = pdfRepository.outline.value
                )
            }
        }
    }

    private suspend fun preloadInitialPage(session: PdfDocumentSession) {
        updateUiState { it.copy(loadingProgress = 0.55f) }
        val targetWidth = viewportWidthPx.coerceAtLeast(480)
        runCatching { pdfRepository.renderPage(0, targetWidth) }
        updateUiState { it.copy(loadingProgress = 0.85f) }
    }

    private suspend fun updateUiState(transform: (PdfViewerUiState) -> PdfViewerUiState) {
        withContext(Dispatchers.Main) {
            _uiState.value = transform(_uiState.value)
        }
    }

    private suspend fun handleDocumentError(throwable: Throwable) {
        updateUiState { current ->
            current.copy(
                isLoading = false,
                loadingProgress = null,
                errorMessage = throwable.message ?: "Unable to open document"
            )
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

    fun toggleBookmark() {
        val documentId = _uiState.value.documentId ?: return
        val currentPage = _uiState.value.currentPage
        viewModelScope.launch {
            bookmarkManager.toggleBookmark(documentId, currentPage)
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
            val results = mutableListOf<SearchResult>()
            for (pageIndex in 0 until session.pageCount) {
                ensureActive()
                val matches = performSearch(pageIndex, query)
                if (matches.isNotEmpty()) {
                    results += SearchResult(pageIndex, matches)
                }
            }
            updateUiState { it.copy(searchResults = results) }
        }
    }

    internal suspend fun performSearch(pageIndex: Int, query: String): List<SearchMatch> {
        val session = pdfRepository.session.value ?: return emptyList()
        val normalizedQuery = normalizeSearchQuery(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        val renderer = session.renderer
        val page = try {
            renderer.openPage(pageIndex)
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                collectMatchesFromRuns(
                    runs = extractTextRuns(page),
                    normalizedQuery = normalizedQuery,
                    pageWidth = page.width,
                    pageHeight = page.height
                )
            } else {
                approximateMatchesFromBitmap(page, normalizedQuery)
            }
        } finally {
            page.close()
        }
    }

    internal open fun extractTextRuns(page: PdfRenderer.Page): List<TextRunSnapshot> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return emptyList()
        }
        val runs = mutableListOf<TextRunSnapshot>()
        val textStructure = try {
            page.javaClass.getMethod("getText").invoke(page)
        } catch (_: ReflectiveOperationException) {
            return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }

        fun collect(element: Any?) {
            when (element) {
                null -> return
                is Collection<*> -> {
                    element.forEach { collect(it) }
                    return
                }
                is Array<*> -> {
                    element.forEach { collect(it) }
                    return
                }
            }
            val glyphRuns = when (val glyphs = tryInvoke(element, "getGlyphRuns")) {
                is Collection<*> -> glyphs
                is Array<*> -> glyphs.asList()
                else -> null
            }
            if (!glyphRuns.isNullOrEmpty()) {
                glyphRuns.forEach { collect(it) }
                return
            }
            val text = (tryInvoke(element, "getText") as? CharSequence)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: return
            val bounds = extractBounds(element)
            if (bounds.isNotEmpty()) {
                runs += TextRunSnapshot(text, bounds)
            }
        }

        collect(textStructure)
        return runs
    }

    private fun extractBounds(source: Any?): List<RectF> {
        val direct = convertToRectList(source)
        if (direct.isNotEmpty()) return direct
        val bounds = tryInvoke(source, "getBounds") ?: return emptyList()
        return convertToRectList(bounds)
    }

    private fun convertToRectList(candidate: Any?): List<RectF> {
        return when (candidate) {
            null -> emptyList()
            is RectF -> listOf(RectF(candidate))
            is Rect -> listOf(RectF(candidate))
            is Collection<*> -> candidate.flatMap { convertToRectList(it) }
            is Array<*> -> candidate.flatMap { convertToRectList(it) }
            else -> emptyList()
        }.filter { it.width() > 0f && it.height() > 0f }
    }

    private fun tryInvoke(target: Any?, methodName: String): Any? {
        if (target == null) return null
        return try {
            target.javaClass.getMethod(methodName).invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun approximateMatchesFromBitmap(
        page: PdfRenderer.Page,
        normalizedQuery: String
    ): List<SearchMatch> {
        val width = page.width
        val height = page.height
        if (width <= 0 || height <= 0) return emptyList()
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return emptyList()
        }
        return try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val regions = detectTextRegions(bitmap)
            if (regions.isEmpty()) {
                emptyList()
            } else {
                val tokenCount = normalizedQuery.split(' ').count { it.isNotBlank() }.coerceAtLeast(1)
                val limitedRegions = regions.take(tokenCount)
                if (limitedRegions.isEmpty()) emptyList() else listOf(SearchMatch(0, limitedRegions))
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun onCleared() {
        super.onCleared()
        adaptiveFlowManager.stop()
        pdfRepository.dispose()
    }

    private fun isNightModeEnabled(): Boolean {
        val configuration = getApplication<Application>().resources.configuration
        val mask = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mask == Configuration.UI_MODE_NIGHT_YES
    }

}
