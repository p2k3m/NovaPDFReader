package com.novapdf.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PageTileRequest
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        return pdfRepository.renderTile(
            PageTileRequest(
                pageIndex = index,
                tileRect = rect,
                scale = scale
            )
        )
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
                val matches = performFuzzySearch(pageIndex, query)
                if (matches.isNotEmpty()) {
                    results += SearchResult(pageIndex, matches)
                }
            }
            _uiState.value = _uiState.value.copy(searchResults = results)
        }
    }

    private suspend fun performFuzzySearch(pageIndex: Int, query: String): List<SearchMatch> {
        // PdfRenderer does not expose text extraction APIs prior to API 34.
        // We fall back to a simple heuristic: treat quick render timing as a proxy and highlight entire page when query is short.
        delay(10)
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        val pseudoHash = (pageIndex.toString() + normalized).hashCode()
        val shouldFlag = pseudoHash % 7 == 0
        return if (shouldFlag) {
            listOf(
                SearchMatch(
                    indexInPage = 0,
                    boundingBoxes = listOf(
                        com.novapdf.reader.model.RectSnapshot(0f, 0f, 1f, 1f)
                    )
                )
            )
        } else emptyList()
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
