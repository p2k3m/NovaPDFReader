package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Size
import android.content.res.Configuration
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.TextRunSnapshot
import com.novapdf.reader.search.collectMatchesFromRuns
import com.novapdf.reader.search.detectTextRegions
import com.novapdf.reader.search.normalizeSearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PdfViewerUiState(
    val documentId: String? = null,
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isNightMode: Boolean = false,
    val readingSpeed: Float = 0f,
    val swipeSensitivity: Float = 1f,
    val searchResults: List<SearchResult> = emptyList(),
    val activeAnnotations: List<AnnotationCommand> = emptyList(),
    val bookmarks: List<Int> = emptyList()
)

data class TilePreloadSpec(
    val pageIndex: Int,
    val tileFractions: List<RectF>,
    val scale: Float
)

class PdfViewerViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val app = application as NovaPdfApp
    private val annotationRepository: AnnotationRepository = app.annotationRepository
    private val pdfRepository: PdfDocumentRepository = app.pdfDocumentRepository
    private val adaptiveFlowManager: AdaptiveFlowManager = app.adaptiveFlowManager
    private val bookmarkManager: BookmarkManager = app.bookmarkManager

    private val _uiState = MutableStateFlow(PdfViewerUiState())
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var lastTileSpec: TilePreloadSpec? = null

    init {
        _uiState.value = _uiState.value.copy(isNightMode = isNightModeEnabled())
        viewModelScope.launch {
            combine(
                adaptiveFlowManager.readingSpeedPagesPerMinute,
                adaptiveFlowManager.swipeSensitivity,
                pdfRepository.session
            ) { speed, sensitivity, session ->
                Triple(speed, sensitivity, session)
            }.collect { (speed, sensitivity, session) ->
                val annotations = session?.let { annotationRepository.annotationsForDocument(it.documentId) }
                    .orEmpty()
                val bookmarks = session?.let { bookmarkManager.bookmarks(it.documentId) } ?: emptyList()
                _uiState.value = _uiState.value.copy(
                    readingSpeed = speed,
                    swipeSensitivity = sensitivity,
                    documentId = session?.documentId,
                    pageCount = session?.pageCount ?: 0,
                    activeAnnotations = annotations,
                    bookmarks = bookmarks
                )
            }
        }
        viewModelScope.launch {
            adaptiveFlowManager.preloadTargets.collect { targets ->
                val spec = lastTileSpec
                if (spec != null && targets.isNotEmpty()) {
                    pdfRepository.preloadTiles(
                        indices = targets,
                        tileFractions = spec.tileFractions,
                        scale = spec.scale
                    )
                }
            }
        }
    }

    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val session = pdfRepository.open(uri)
            if (session == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Unable to open document")
                return@launch
            }
            annotationRepository.clearInMemory(session.documentId)
            lastTileSpec = null
            adaptiveFlowManager.start()
            adaptiveFlowManager.trackPageChange(0, session.pageCount)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                documentId = session.documentId,
                pageCount = session.pageCount,
                currentPage = 0,
                errorMessage = null,
                activeAnnotations = emptyList(),
                bookmarks = bookmarkManager.bookmarks(session.documentId)
            )
        }
    }

    fun onPageFocused(index: Int) {
        val session = pdfRepository.session.value ?: return
        if (index !in 0 until session.pageCount) return
        adaptiveFlowManager.trackPageChange(index, session.pageCount)
        _uiState.value = _uiState.value.copy(currentPage = index)
        val preloadTargets = adaptiveFlowManager.preloadTargets.value
        val spec = lastTileSpec
        if (spec != null) {
            pdfRepository.preloadTiles(
                indices = preloadTargets,
                tileFractions = spec.tileFractions,
                scale = spec.scale
            )
        }
    }

    suspend fun renderTile(index: Int, rect: Rect, scale: Float): Bitmap? {
        return pdfRepository.renderTile(index, rect, scale)
    }

    suspend fun pageSize(index: Int): Size? {
        return pdfRepository.getPageSize(index)
    }

    fun updateTileSpec(spec: TilePreloadSpec) {
        lastTileSpec = spec
        val preloadTargets = adaptiveFlowManager.preloadTargets.value
        if (preloadTargets.isNotEmpty()) {
            pdfRepository.preloadTiles(
                indices = preloadTargets,
                tileFractions = spec.tileFractions,
                scale = spec.scale
            )
        }
    }

    fun addAnnotation(annotation: AnnotationCommand) {
        val documentId = _uiState.value.documentId ?: return
        annotationRepository.addAnnotation(documentId, annotation)
        _uiState.value = _uiState.value.copy(
            activeAnnotations = annotationRepository.annotationsForDocument(documentId)
        )
    }

    fun toggleBookmark() {
        val documentId = _uiState.value.documentId ?: return
        val currentPage = _uiState.value.currentPage
        viewModelScope.launch {
            bookmarkManager.toggleBookmark(documentId, currentPage)
            _uiState.value = _uiState.value.copy(
                bookmarks = bookmarkManager.bookmarks(documentId)
            )
        }
    }

    fun persistAnnotations() {
        val documentId = _uiState.value.documentId ?: return
        viewModelScope.launch {
            annotationRepository.saveAnnotations(documentId)
        }
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
                val matches = performSearch(pageIndex, query)
                if (matches.isNotEmpty()) {
                    results += SearchResult(pageIndex, matches)
                }
            }
            _uiState.value = _uiState.value.copy(searchResults = results)
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

    private fun extractTextRuns(page: PdfRenderer.Page): List<TextRunSnapshot> {
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
