package com.novapdf.reader.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.Process
import android.util.Base64
import com.novapdf.reader.logging.NovaLog
import android.system.ErrnoException
import android.system.Os
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.novapdf.reader.cache.PdfCacheRoot
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PIPELINE_PROGRESS_TIMEOUT_MS
import com.novapdf.reader.data.PipelineType
import com.novapdf.reader.data.signalPipelineProgress
import com.novapdf.reader.data.withPipelineWatchdog
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchIndexingPhase
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.ParseException
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.FSDirectory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.text.Charsets
import kotlin.math.max
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.io.buffered
import kotlin.jvm.Volatile

private const val TAG = "LuceneSearchCoordinator"
private const val OCR_RENDER_TARGET_WIDTH = 1280
private const val MAX_INDEXED_PAGE_COUNT = 400
private val WHOLE_PAGE_RECT = RectSnapshot(0f, 0f, 1f, 1f)
private const val CONTENT_RESOLVER_READ_TIMEOUT_MS = 30_000L
private const val OCR_BATCH_SIZE = 4
private const val OCR_SCROLL_GUARD_PAGE_LIMIT = 4
private const val OCR_SCROLL_GUARD_IDLE_TIMEOUT_MS = 1_000L
private const val CACHE_VERSION = 1
private const val METADATA_FILE_NAME = "metadata.json"
private const val PAGE_CACHE_DIRECTORY_NAME = "pages"
private const val PAGE_CACHE_FILE_PREFIX = "page-"
private const val PAGE_CACHE_FILE_SUFFIX = ".json"

private data class PageSearchContent(
    val text: String,
    val normalizedText: String,
    val runs: List<TextRunSnapshot>,
    val coordinateWidth: Int,
    val coordinateHeight: Int,
    val fallbackRegions: List<RectSnapshot>
)

private data class OcrLine(
    val text: String,
    val bounds: Rect
)

private data class OcrPageResult(
    val text: String,
    val lines: List<OcrLine>,
    val fallbackRegions: List<RectSnapshot>,
    val bitmapWidth: Int,
    val bitmapHeight: Int
)

private data class OcrFallbackRequest(
    val pageIndex: Int,
    val coordinateWidth: Int,
    val coordinateHeight: Int,
    val needsText: Boolean,
    val needsBounds: Boolean,
)

private data class DocumentIndexShard(
    val documentId: String,
    val directory: Directory?,
    val reader: DirectoryReader?,
    val searcher: IndexSearcher?,
    val contents: List<PageSearchContent>
) : Closeable {
    override fun close() {
        runCatching { reader?.close() }
        runCatching { directory?.close() }
    }
}

private data class PageContentResult(
    val contents: List<PageSearchContent>,
    val fromCache: Boolean
)

private data class DocumentCacheMetadata(
    val version: Int,
    val pageCount: Int,
    val documentMtimeMs: Long,
)

private const val INDEX_POOL_PARALLELISM = 1
private const val EXTRACTION_WEIGHT = 0.7f
private const val OCR_WEIGHT = 0.2f
private const val INDEX_WEIGHT = 0.1f

private const val OCR_BASE = EXTRACTION_WEIGHT
private const val INDEX_BASE = EXTRACTION_WEIGHT + OCR_WEIGHT

class LuceneSearchCoordinator(
    private val context: Context,
    private val pdfRepository: PdfDocumentRepository,
    private val dispatchers: CoroutineDispatchers,
    scopeProvider: (CoroutineDispatcher) -> CoroutineScope = { dispatcher ->
        CoroutineScope(SupervisorJob() + dispatcher)
    }
) : DocumentSearchCoordinator {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val indexDispatcherHandle = createIndexDispatcher(dispatchers.io)
    private val indexDispatcher: CoroutineDispatcher = indexDispatcherHandle.dispatcher

    private val scope = scopeProvider(indexDispatcher)
    private val appContext = context.applicationContext
    private val indexRoot = PdfCacheRoot.indexes(appContext)
    private val analyzer = StandardAnalyzer()
    private val shardLocks = ConcurrentHashMap<String, Mutex>()
    private val indexShards = ConcurrentHashMap<String, DocumentIndexShard>()
    private var indexSearcher: IndexSearcher? = null
    private var currentDocumentId: String? = null
    private var prepareJob: Job? = null
    private val textRecognizer: TextRecognizer? = runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }.onFailure { error ->
        NovaLog.w(TAG, "Text recognition unavailable; OCR fallback disabled", error)
    }.getOrNull()
    @Volatile
    private var pageContents: List<PageSearchContent> = emptyList()

    private val indexingStateFlow = MutableStateFlow<SearchIndexingState>(SearchIndexingState.Idle)
    override val indexingState: StateFlow<SearchIndexingState> = indexingStateFlow.asStateFlow()

    private fun emitIndexingState(
        documentId: String,
        progress: Float?,
        phase: SearchIndexingPhase,
    ) {
        runCatching { signalPipelineProgress() }
        val normalized = progress?.coerceIn(0f, 1f)
        val current = indexingStateFlow.value
        if (current is SearchIndexingState.InProgress && current.documentId != documentId) {
            return
        }
        indexingStateFlow.value = SearchIndexingState.InProgress(documentId, normalized, phase)
    }

    private fun clearIndexingState(documentId: String) {
        val current = indexingStateFlow.value
        if (current is SearchIndexingState.InProgress && current.documentId != documentId) {
            return
        }
        indexingStateFlow.value = SearchIndexingState.Idle
    }

    private fun stageProgress(base: Float, weight: Float, stageProgress: Float): Float {
        return (base + weight * stageProgress).coerceIn(0f, 1f)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private data class DispatcherHandle(
        val dispatcher: CoroutineDispatcher,
        val shutdown: (() -> Unit)? = null,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun createIndexDispatcher(base: CoroutineDispatcher): DispatcherHandle {
        return try {
            val threadFactory = ThreadFactory { runnable ->
                Thread({
                    try {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } catch (error: Throwable) {
                        NovaLog.w(TAG, "Unable to set Lucene index thread priority", error)
                    }
                    runnable.run()
                }, "LuceneIndex").apply {
                    priority = Thread.MIN_PRIORITY
                }
            }
            val executor = Executors.newSingleThreadExecutor(threadFactory)
            val dispatcher = executor.asCoroutineDispatcher()
            DispatcherHandle(dispatcher) {
                dispatcher.close()
            }
        } catch (error: Throwable) {
            NovaLog.w(TAG, "Unable to create dedicated dispatcher for Lucene indexing", error)
            val fallback = try {
                base.limitedParallelism(INDEX_POOL_PARALLELISM)
            } catch (unsupported: UnsupportedOperationException) {
                NovaLog.w(
                    TAG,
                    "Unable to limit parallelism for provided dispatcher; falling back to Dispatchers.IO",
                    unsupported
                )
                kotlinx.coroutines.Dispatchers.IO.limitedParallelism(INDEX_POOL_PARALLELISM)
            }
            DispatcherHandle(fallback, null)
        }
    }

    override fun prepare(session: PdfDocumentSession): Job? {
        prepareJob?.cancel()
        val documentId = session.documentId
        if (session.pageCount > MAX_INDEXED_PAGE_COUNT) {
            NovaLog.i(
                TAG,
                "Skipping Lucene index preparation for large document " +
                    "(${session.pageCount} pages)"
            )
            clearStoredIndex(session.documentId)
            currentDocumentId = session.documentId
            indexSearcher = null
            pageContents = emptyList()
            clearIndexingState(documentId)
            prepareJob = null
            return null
        }
        emitIndexingState(documentId, progress = 0f, phase = SearchIndexingPhase.PREPARING)
        prepareJob = scope.launch {
            try {
                rebuildIndex(session)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                NovaLog.w(TAG, "Failed to pre-build index", error)
            } finally {
                clearIndexingState(documentId)
            }
        }
        return prepareJob
    }

    override suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        ensureIndexReady(session)
        val searcher = indexSearcher ?: return emptyList()
        val luceneQuery = buildQuery(query)
        val normalizedQuery = normalizeSearchQuery(query)
        val contentsSnapshot = pageContents
        val maxDocs = max(1, searcher.indexReader.maxDoc())
        val topDocs = try {
            searcher.search(luceneQuery, maxDocs)
        } catch (parse: Exception) {
            NovaLog.w(TAG, "Lucene search failed", parse)
            return emptyList()
        }
        if (topDocs.scoreDocs.isEmpty()) return emptyList()

        val resultsByPage = linkedMapOf<Int, MutableList<SearchMatch>>()
        for (scoreDoc in topDocs.scoreDocs) {
            val doc = searcher.doc(scoreDoc.doc)
            val pageField = doc.getField("page") ?: continue
            val pageIndex = pageField.numericValue()?.toInt() ?: continue
            val matches = resultsByPage.getOrPut(pageIndex) { mutableListOf() }
            val content = contentsSnapshot.getOrNull(pageIndex)
            val fromRuns = content?.let {
                collectMatchesFromRuns(it.runs, normalizedQuery, it.coordinateWidth, it.coordinateHeight)
            }.orEmpty()
            if (fromRuns.isNotEmpty()) {
                fromRuns.forEachIndexed { offset, match ->
                    matches += SearchMatch(
                        indexInPage = matches.size + offset,
                        boundingBoxes = match.boundingBoxes
                    )
                }
                continue
            }
            val normalizedText = content?.normalizedText.orEmpty()
            val occurrenceCount = countOccurrences(normalizedText, normalizedQuery)
            val fallbackRects = content?.fallbackRegions?.takeIf { it.isNotEmpty() } ?: listOf(WHOLE_PAGE_RECT)
            val total = max(1, occurrenceCount)
            repeat(total) { offset ->
                matches += SearchMatch(
                    indexInPage = matches.size + offset,
                    boundingBoxes = fallbackRects
                )
            }
        }
        return resultsByPage.entries
            .sortedBy { it.key }
            .map { (pageIndex, matches) -> SearchResult(pageIndex, matches) }
    }

    override fun dispose() {
        prepareJob?.cancel()
        prepareJob = null
        scope.cancel()
        runCatching { textRecognizer?.close() }
        indexShards.values.forEach { shard ->
            runCatching { shard.close() }
        }
        indexShards.clear()
        shardLocks.clear()
        indexSearcher = null
        currentDocumentId = null
        pageContents = emptyList()
        indexingStateFlow.value = SearchIndexingState.Idle
        indexDispatcherHandle.shutdown?.let { shutdown ->
            runCatching { shutdown() }
        }
    }

    private suspend fun ensureIndexReady(session: PdfDocumentSession) {
        prepareJob?.let { job -> if (job.isActive) job.join() }
        if (currentDocumentId == session.documentId && indexSearcher != null) {
            return
        }
        if (session.pageCount > MAX_INDEXED_PAGE_COUNT) {
            NovaLog.i(
                TAG,
                "Skipping Lucene index rebuild for large document (${session.pageCount} pages)"
            )
            clearStoredIndex(session.documentId)
            currentDocumentId = session.documentId
            indexSearcher = null
            pageContents = emptyList()
            return
        }
        rebuildIndex(session)
    }

    private suspend fun rebuildIndex(session: PdfDocumentSession) = withContext(indexDispatcher) {
        withPipelineWatchdog(
            pipeline = PipelineType.INDEX,
            timeoutMillis = PIPELINE_PROGRESS_TIMEOUT_MS,
            onTimeout = { timeout ->
                NovaLog.w(
                    TAG,
                    "Index pipeline timed out for ${session.documentId}",
                    throwable = timeout,
                )
            },
        ) {
            val documentMtime = readDocumentMtime(session)
            val shard = obtainShard(session, documentMtime = documentMtime)
            applyActiveShard(session.documentId, shard)
            emitIndexingState(session.documentId, progress = 1f, phase = SearchIndexingPhase.WRITING_INDEX)
        }
    }

    private suspend fun extractPageContent(
        session: PdfDocumentSession,
        documentMtime: Long?,
        useCache: Boolean,
    ): PageContentResult {
        val pageCount = session.pageCount
        val documentId = session.documentId
        if (pageCount <= 0) {
            emitIndexingState(
                documentId,
                progress = stageProgress(0f, EXTRACTION_WEIGHT, 1f),
                phase = SearchIndexingPhase.EXTRACTING_TEXT,
            )
            return PageContentResult(emptyList(), fromCache = true)
        }
        if (useCache) {
            val cached = loadCachedPageContents(session.documentId, pageCount, documentMtime)
            if (cached != null) {
                emitIndexingState(
                    documentId,
                    progress = stageProgress(0f, EXTRACTION_WEIGHT, 1f),
                    phase = SearchIndexingPhase.EXTRACTING_TEXT,
                )
                return PageContentResult(cached, fromCache = true)
            }
        }
        val pdfBoxReady = PdfBoxInitializer.ensureInitialized(context)
        if (!pdfBoxReady) {
            NovaLog.w(TAG, "PDFBox initialisation failed; skipping text extraction")
            return PageContentResult(emptyList(), fromCache = false)
        }
        emitIndexingState(documentId, progress = 0f, phase = SearchIndexingPhase.EXTRACTING_TEXT)
        val contents = MutableList(pageCount) {
            PageSearchContent(
                text = "",
                normalizedText = "",
                runs = emptyList(),
                coordinateWidth = 1,
                coordinateHeight = 1,
                fallbackRegions = emptyList(),
            )
        }
        try {
            context.contentResolver.openInputStream(session.uri)?.buffered()?.use { input ->
                withTimeout(CONTENT_RESOLVER_READ_TIMEOUT_MS) {
                    PDDocument.load(input).use { document ->
                        for (page in 0 until pageCount) {
                            ensureActive()
                            val pdPage = runCatching { document.getPage(page) }
                                .onFailure { NovaLog.w(TAG, "Failed to read PDF page $page", it) }
                                .getOrNull() ?: continue
                            try {
                                val pageWidth = pdPage.mediaBox?.width?.toInt()?.takeIf { it > 0 } ?: 1
                                val pageHeight = pdPage.mediaBox?.height?.toInt()?.takeIf { it > 0 } ?: 1
                                val runs = mutableListOf<TextRunSnapshot>()
                                val pageWidthF = pageWidth.toFloat()
                                val pageHeightF = pageHeight.toFloat()
                                val writer = StringWriter()
                                val stripper = object : PDFTextStripper() {
                                    override fun writeString(text: String?, textPositions: List<TextPosition>?) {
                                        if (!text.isNullOrEmpty() && !textPositions.isNullOrEmpty()) {
                                            val bounds = buildList {
                                                textPositions.forEach { position ->
                                                    val width = position.widthDirAdj
                                                    val height = position.heightDir
                                                    if (width <= 0f || height <= 0f) return@forEach
                                                    var left = position.xDirAdj
                                                    var top = position.yDirAdj - height
                                                    var right = left + width
                                                    var bottom = top + height
                                                    left = left.coerceIn(0f, pageWidthF)
                                                    top = top.coerceIn(0f, pageHeightF)
                                                    right = right.coerceIn(0f, pageWidthF)
                                                    bottom = bottom.coerceIn(0f, pageHeightF)
                                                    if (right - left > 0f && bottom - top > 0f) {
                                                        add(RectF(left, top, right, bottom))
                                                    }
                                                }
                                            }
                                            if (bounds.isNotEmpty()) {
                                                runs += TextRunSnapshot(text, bounds)
                                            }
                                        }
                                        super.writeString(text, textPositions)
                                    }
                                }.apply {
                                    startPage = page + 1
                                    endPage = page + 1
                                    sortByPosition = true
                                }
                                stripper.writeText(document, writer)
                                val rawText = writer.toString()
                                val normalizedText = normalizeSearchQuery(rawText)
                                contents[page] = PageSearchContent(
                                    text = rawText,
                                    normalizedText = normalizedText,
                                    runs = runs,
                                    coordinateWidth = pageWidth,
                                    coordinateHeight = pageHeight,
                                    fallbackRegions = emptyList(),
                                )
                                val extractionProgress = stageProgress(
                                    base = 0f,
                                    weight = EXTRACTION_WEIGHT,
                                    stageProgress = (page + 1).toFloat() / pageCount,
                                )
                                emitIndexingState(
                                    documentId,
                                    progress = extractionProgress,
                                    phase = SearchIndexingPhase.EXTRACTING_TEXT,
                                )
                            } finally {
                                if (pdPage is Closeable) {
                                    runCatching { pdPage.close() }
                                        .onFailure { NovaLog.w(TAG, "Failed to close PDFBox page $page", it) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            NovaLog.w(TAG, "Timed out while extracting text for search", timeout)
        } catch (io: IOException) {
            NovaLog.w(TAG, "Failed to extract text with PDFBox", io)
        }
        emitIndexingState(
            documentId,
            progress = stageProgress(0f, EXTRACTION_WEIGHT, 1f),
            phase = SearchIndexingPhase.EXTRACTING_TEXT,
        )
        val pendingOcr = buildList {
            contents.forEachIndexed { index, content ->
                val needsOcrForText = content.text.isBlank()
                val needsOcrForBounds = content.runs.isEmpty()
                if (!needsOcrForText && !needsOcrForBounds) {
                    return@forEachIndexed
                }
                add(
                    OcrFallbackRequest(
                        pageIndex = index,
                        coordinateWidth = content.coordinateWidth,
                        coordinateHeight = content.coordinateHeight,
                        needsText = needsOcrForText,
                        needsBounds = needsOcrForBounds,
                    ),
                )
            }
        }
        applyOcrFallbacks(documentId, contents, pendingOcr)
        val finalContents = contents.toList()
        if (documentMtime != null) {
            persistPageContents(session.documentId, documentMtime, finalContents)
        }
        return PageContentResult(finalContents, fromCache = false)
    }

    private suspend fun applyOcrFallbacks(
        documentId: String,
        contents: MutableList<PageSearchContent>,
        pending: List<OcrFallbackRequest>,
    ) {
        if (pending.isEmpty()) return
        emitIndexingState(
            documentId,
            progress = OCR_BASE,
            phase = SearchIndexingPhase.APPLYING_OCR,
        )
        var processed = 0
        val total = pending.size
        for (batch in pending.chunked(OCR_BATCH_SIZE)) {
            coroutineContext.ensureActive()
            waitForRenderIdleIfNeeded(batch)
            for (request in batch) {
                coroutineContext.ensureActive()
                val fallback = runCatching {
                    performOcr(request.pageIndex, request.coordinateWidth, request.coordinateHeight)
                }.onFailure {
                    NovaLog.w(TAG, "OCR fallback failed for page ${request.pageIndex}", it)
                }.getOrNull() ?: continue
                val existing = contents[request.pageIndex]
                contents[request.pageIndex] = mergeOcrFallback(existing, fallback, request)
                processed++
                val ocrProgress = stageProgress(
                    base = OCR_BASE,
                    weight = OCR_WEIGHT,
                    stageProgress = processed.toFloat() / total,
                )
                emitIndexingState(
                    documentId,
                    progress = ocrProgress,
                    phase = SearchIndexingPhase.APPLYING_OCR,
                )
            }
        }
    }

    private fun mergeOcrFallback(
        original: PageSearchContent,
        fallback: PageSearchContent,
        request: OcrFallbackRequest,
    ): PageSearchContent {
        val text = if (request.needsText && fallback.text.isNotBlank()) {
            fallback.text
        } else {
            original.text
        }
        val normalized = if (request.needsText && fallback.normalizedText.isNotBlank()) {
            fallback.normalizedText
        } else {
            original.normalizedText
        }
        val runs = if (request.needsBounds && fallback.runs.isNotEmpty()) {
            fallback.runs
        } else {
            original.runs
        }
        val fallbackRegions = if (fallback.fallbackRegions.isNotEmpty()) {
            fallback.fallbackRegions
        } else {
            original.fallbackRegions
        }
        return original.copy(
            text = text,
            normalizedText = normalized,
            runs = runs,
            fallbackRegions = fallbackRegions,
        )
    }

    private suspend fun waitForRenderIdleIfNeeded(batch: List<OcrFallbackRequest>) {
        if (batch.none { it.pageIndex < OCR_SCROLL_GUARD_PAGE_LIMIT }) return
        withTimeoutOrNull(OCR_SCROLL_GUARD_IDLE_TIMEOUT_MS) {
            pdfRepository.renderProgress.first { progress ->
                !progress.isRenderingGuardedPage()
            }
        }
    }

    private fun PdfRenderProgress.isRenderingGuardedPage(): Boolean {
        return this is PdfRenderProgress.Rendering &&
            pageIndex < OCR_SCROLL_GUARD_PAGE_LIMIT &&
            progress < 1f
    }

    private suspend fun performOcr(
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): PageSearchContent? = withContext(indexDispatcher) {
        val cancellationSignal = CancellationSignal()
        val cancelHandle: DisposableHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                cancellationSignal.cancel()
            }
        }
        val bitmap = try {
            pdfRepository.renderPage(
                pageIndex,
                OCR_RENDER_TARGET_WIDTH,
                cancellationSignal = cancellationSignal,
            )
        } finally {
            cancelHandle.dispose()
        } ?: return@withContext null
        try {
            ensureActive()
            val recognized = recognizeText(bitmap)
            val normalizedText = normalizeSearchQuery(recognized.text)
            val pageWidthF = pageWidth.toFloat()
            val pageHeightF = pageHeight.toFloat()
            val runs = recognized.lines.mapNotNull { line ->
                val bounds = line.bounds
                if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null
                val scaleX = if (recognized.bitmapWidth > 0) pageWidth.toFloat() / recognized.bitmapWidth else 1f
                val scaleY = if (recognized.bitmapHeight > 0) pageHeight.toFloat() / recognized.bitmapHeight else 1f
                var left = bounds.left * scaleX
                var top = bounds.top * scaleY
                var right = bounds.right * scaleX
                var bottom = bounds.bottom * scaleY
                left = left.coerceIn(0f, pageWidthF)
                top = top.coerceIn(0f, pageHeightF)
                right = right.coerceIn(0f, pageWidthF)
                bottom = bottom.coerceIn(0f, pageHeightF)
                if (right - left <= 0f || bottom - top <= 0f) return@mapNotNull null
                TextRunSnapshot(
                    text = line.text,
                    bounds = listOf(RectF(left, top, right, bottom))
                )
            }
            PageSearchContent(
                text = recognized.text,
                normalizedText = normalizedText,
                runs = runs,
                coordinateWidth = pageWidth,
                coordinateHeight = pageHeight,
                fallbackRegions = recognized.fallbackRegions
            )
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun readDocumentMtime(session: PdfDocumentSession): Long? {
        return try {
            val stat = Os.fstat(session.fileDescriptor.fileDescriptor)
            val seconds = stat.st_mtime
            java.lang.Math.multiplyExact(seconds, 1_000L)
        } catch (error: ErrnoException) {
            NovaLog.w(TAG, "Unable to stat document for OCR cache", error)
            null
        } catch (overflow: ArithmeticException) {
            NovaLog.w(TAG, "Document modification time overflow", overflow)
            null
        } catch (unexpected: Throwable) {
            NovaLog.w(TAG, "Unexpected error while reading document mtime", unexpected)
            null
        }
    }

    private fun loadCachedPageContents(
        documentId: String,
        expectedPageCount: Int,
        documentMtime: Long?,
    ): List<PageSearchContent>? {
        if (documentMtime == null) return null
        val metadata = readDocumentMetadata(documentId) ?: return null
        if (metadata.version != CACHE_VERSION) return null
        if (metadata.pageCount != expectedPageCount) return null
        if (metadata.documentMtimeMs != documentMtime) return null
        val pagesDir = documentPageCacheDirectory(documentId, createIfMissing = false) ?: return null
        val contents = ArrayList<PageSearchContent>(expectedPageCount)
        for (index in 0 until expectedPageCount) {
            val file = File(pagesDir, pageCacheFileName(index))
            val content = readCachedPageContent(file) ?: return null
            contents += content
        }
        return contents
    }

    private fun persistPageContents(
        documentId: String,
        documentMtime: Long,
        contents: List<PageSearchContent>,
    ) {
        val directory = documentIndexDirectory(documentId) ?: return
        val pagesDir = File(directory, PAGE_CACHE_DIRECTORY_NAME)
        if (!ensureDirectoryExists(pagesDir)) {
            return
        }
        var success = true
        contents.forEachIndexed { index, content ->
            val file = File(pagesDir, pageCacheFileName(index))
            val json = pageContentToJson(content)
            if (!writeJsonSafely(file, json)) {
                success = false
            }
        }
        if (!success) {
            NovaLog.w(TAG, "Skipping OCR cache metadata update for $documentId due to write failures")
            return
        }
        cleanupStalePageFiles(pagesDir, contents.size)
        val metadata = DocumentCacheMetadata(
            version = CACHE_VERSION,
            pageCount = contents.size,
            documentMtimeMs = documentMtime,
        )
        writeDocumentMetadata(directory, metadata)
    }

    private fun readDocumentMetadata(documentId: String): DocumentCacheMetadata? {
        val directory = documentIndexDirectory(documentId, createIfMissing = false) ?: return null
        val file = File(directory, METADATA_FILE_NAME)
        if (!file.exists()) {
            return null
        }
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val json = JSONObject(raw)
            val version = json.optInt("version", -1)
            val pageCount = json.optInt("pageCount", -1)
            val documentMtime = json.optLong("documentMtimeMs", -1L)
            if (version <= 0 || pageCount < 0 || documentMtime < 0L) {
                null
            } else {
                DocumentCacheMetadata(version, pageCount, documentMtime)
            }
        } catch (error: IOException) {
            NovaLog.w(TAG, "Failed to read OCR cache metadata for $documentId", error)
            null
        } catch (error: JSONException) {
            NovaLog.w(TAG, "Malformed OCR cache metadata for $documentId", error)
            null
        }
    }

    private fun writeDocumentMetadata(directory: File, metadata: DocumentCacheMetadata) {
        val file = File(directory, METADATA_FILE_NAME)
        val json = JSONObject()
            .put("version", metadata.version)
            .put("pageCount", metadata.pageCount)
            .put("documentMtimeMs", metadata.documentMtimeMs)
        if (!writeJsonSafely(file, json)) {
            NovaLog.w(TAG, "Failed to persist OCR cache metadata at ${file.absolutePath}")
        }
    }

    private fun documentPageCacheDirectory(
        documentId: String,
        createIfMissing: Boolean,
    ): File? {
        val directory = documentIndexDirectory(documentId, createIfMissing) ?: return null
        val pagesDir = File(directory, PAGE_CACHE_DIRECTORY_NAME)
        if (!pagesDir.exists()) {
            if (!createIfMissing) return null
            if (!pagesDir.mkdirs() && !pagesDir.isDirectory) {
                NovaLog.w(TAG, "Unable to create OCR page cache at ${pagesDir.absolutePath}")
                return null
            }
        }
        if (!pagesDir.isDirectory) {
            NovaLog.w(TAG, "OCR page cache path is not a directory: ${pagesDir.absolutePath}")
            return null
        }
        return pagesDir
    }

    private fun readCachedPageContent(file: File): PageSearchContent? {
        if (!file.exists()) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            val json = JSONObject(raw)
            val text = json.optString("text", "")
            val normalized = json.optString("normalizedText", "")
            val coordinateWidth = json.optInt("coordinateWidth", 1)
            val coordinateHeight = json.optInt("coordinateHeight", 1)
            val runsArray = json.optJSONArray("runs") ?: JSONArray()
            val runs = buildList {
                for (i in 0 until runsArray.length()) {
                    val runObject = runsArray.optJSONObject(i) ?: continue
                    val runText = runObject.optString("text", "")
                    val boundsArray = runObject.optJSONArray("bounds") ?: JSONArray()
                    val bounds = buildList {
                        for (j in 0 until boundsArray.length()) {
                            val rect = boundsArray.optJSONObject(j) ?: continue
                            val left = rect.optDouble("left", Double.NaN).toFloat()
                            val top = rect.optDouble("top", Double.NaN).toFloat()
                            val right = rect.optDouble("right", Double.NaN).toFloat()
                            val bottom = rect.optDouble("bottom", Double.NaN).toFloat()
                            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                                continue
                            }
                            add(RectF(left, top, right, bottom))
                        }
                    }
                    add(TextRunSnapshot(runText, bounds))
                }
            }
            val fallbackArray = json.optJSONArray("fallbackRegions") ?: JSONArray()
            val fallback = buildList {
                for (i in 0 until fallbackArray.length()) {
                    val rect = fallbackArray.optJSONObject(i) ?: continue
                    val left = rect.optDouble("left", Double.NaN).toFloat()
                    val top = rect.optDouble("top", Double.NaN).toFloat()
                    val right = rect.optDouble("right", Double.NaN).toFloat()
                    val bottom = rect.optDouble("bottom", Double.NaN).toFloat()
                    if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                        continue
                    }
                    add(RectSnapshot(left, top, right, bottom))
                }
            }
            PageSearchContent(
                text = text,
                normalizedText = normalized,
                runs = runs,
                coordinateWidth = coordinateWidth,
                coordinateHeight = coordinateHeight,
                fallbackRegions = fallback,
            )
        } catch (error: IOException) {
            NovaLog.w(TAG, "Failed to read OCR cache page at ${file.absolutePath}", error)
            null
        } catch (error: JSONException) {
            NovaLog.w(TAG, "Malformed OCR cache page at ${file.absolutePath}", error)
            null
        }
    }

    private fun pageContentToJson(content: PageSearchContent): JSONObject {
        val runsArray = JSONArray()
        content.runs.forEach { run ->
            val boundsArray = JSONArray()
            run.bounds.forEach { rect ->
                boundsArray.put(
                    JSONObject()
                        .put("left", rect.left.toDouble())
                        .put("top", rect.top.toDouble())
                        .put("right", rect.right.toDouble())
                        .put("bottom", rect.bottom.toDouble())
                )
            }
            runsArray.put(
                JSONObject()
                    .put("text", run.text)
                    .put("bounds", boundsArray)
            )
        }
        val fallbackArray = JSONArray()
        content.fallbackRegions.forEach { rect ->
            fallbackArray.put(
                JSONObject()
                    .put("left", rect.left.toDouble())
                    .put("top", rect.top.toDouble())
                    .put("right", rect.right.toDouble())
                    .put("bottom", rect.bottom.toDouble())
            )
        }
        return JSONObject()
            .put("text", content.text)
            .put("normalizedText", content.normalizedText)
            .put("coordinateWidth", content.coordinateWidth)
            .put("coordinateHeight", content.coordinateHeight)
            .put("runs", runsArray)
            .put("fallbackRegions", fallbackArray)
    }

    private fun writeJsonSafely(file: File, json: JSONObject): Boolean {
        return writeTextSafely(file, json.toString())
    }

    private fun writeTextSafely(file: File, text: String): Boolean {
        val parent = file.parentFile
        if (parent != null && !ensureDirectoryExists(parent)) {
            return false
        }
        return try {
            val temp = File.createTempFile(file.name, ".tmp", parent)
            try {
                temp.writeText(text, Charsets.UTF_8)
                if (temp.renameTo(file) || (file.delete() && temp.renameTo(file))) {
                    true
                } else {
                    NovaLog.w(TAG, "Unable to move OCR cache file to ${file.absolutePath}")
                    temp.delete()
                    false
                }
            } finally {
                if (temp.exists() && temp != file) {
                    temp.delete()
                }
            }
        } catch (error: IOException) {
            NovaLog.w(TAG, "Failed to write OCR cache file at ${file.absolutePath}", error)
            false
        }
    }

    private fun ensureDirectoryExists(directory: File): Boolean {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                NovaLog.w(TAG, "OCR cache path is not a directory: ${directory.absolutePath}")
                return false
            }
            return true
        }
        if (!directory.mkdirs()) {
            NovaLog.w(TAG, "Unable to create OCR cache directory at ${directory.absolutePath}")
            return false
        }
        return true
    }

    private fun cleanupStalePageFiles(directory: File, expectedCount: Int) {
        val children = directory.listFiles()
        if (children == null) {
            NovaLog.w(TAG, "Unable to list OCR cache directory at ${directory.absolutePath}")
            return
        }
        children.forEach { file ->
            val index = parsePageIndex(file.name)
            if (index == null || index < 0 || index >= expectedCount) {
                if (file.exists() && !file.delete()) {
                    NovaLog.w(TAG, "Unable to delete stale OCR cache page at ${file.absolutePath}")
                }
            }
        }
    }

    private fun pageCacheFileName(pageIndex: Int): String {
        return PAGE_CACHE_FILE_PREFIX + pageIndex + PAGE_CACHE_FILE_SUFFIX
    }

    private fun parsePageIndex(name: String): Int? {
        if (!name.startsWith(PAGE_CACHE_FILE_PREFIX) || !name.endsWith(PAGE_CACHE_FILE_SUFFIX)) {
            return null
        }
        val start = PAGE_CACHE_FILE_PREFIX.length
        val end = name.length - PAGE_CACHE_FILE_SUFFIX.length
        if (end <= start) {
            return null
        }
        return name.substring(start, end).toIntOrNull()
    }

    private fun documentIndexDirectory(
        documentId: String,
        createIfMissing: Boolean = true,
    ): File? {
        if (documentId.isBlank()) {
            NovaLog.w(TAG, "Document ID unavailable; skipping Lucene index persistence")
            return null
        }
        val encodedId = encodeDocumentId(documentId)
        val directory = File(indexRoot, encodedId)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                NovaLog.w(TAG, "Lucene index path is not a directory: ${directory.absolutePath}")
                return null
            }
            return directory
        }
        if (!createIfMissing) {
            return null
        }
        return if (directory.mkdirs() || directory.isDirectory) {
            directory
        } else {
            NovaLog.w(TAG, "Unable to create Lucene index directory at ${directory.absolutePath}")
            null
        }
    }

    private fun clearStoredIndex(documentId: String) {
        removeShard(documentId)
        deleteIndexDirectory(documentId)
    }

    private fun removeShard(documentId: String) {
        val removed = indexShards.remove(documentId)
        removed?.close()
        shardLocks.remove(documentId)
        if (currentDocumentId == documentId) {
            indexSearcher = null
            pageContents = emptyList()
            currentDocumentId = null
        }
    }

    private fun shardLock(documentId: String): Mutex =
        shardLocks.getOrPut(documentId) { Mutex() }

    private fun deleteIndexDirectory(documentId: String) {
        val directory = documentIndexDirectory(documentId, createIfMissing = false) ?: return
        if (!directory.exists()) {
            return
        }
        val deleted = runCatching { directory.deleteRecursively() }
            .onFailure { error ->
                NovaLog.w(TAG, "Unable to delete Lucene index at ${directory.absolutePath}", error)
            }
            .getOrDefault(false)
        if (!deleted && directory.exists()) {
            NovaLog.w(TAG, "Failed to remove Lucene index directory at ${directory.absolutePath}")
        }
    }

    private suspend fun obtainShard(
        session: PdfDocumentSession,
        documentMtime: Long?,
        forceRebuild: Boolean = false,
    ): DocumentIndexShard {
        val documentId = session.documentId
        if (!forceRebuild) {
            indexShards[documentId]?.let { return it }
        }
        val pageResult = if (session.pageCount > 0) {
            extractPageContent(session, documentMtime, useCache = !forceRebuild)
        } else {
            PageContentResult(emptyList(), fromCache = true)
        }
        val contents = pageResult.contents
        val lock = shardLock(documentId)
        return lock.withLock {
            if (!forceRebuild) {
                indexShards[documentId]?.let { return@withLock it }
            } else {
                indexShards.remove(documentId)?.close()
            }
            if (contents.isEmpty()) {
                deleteIndexDirectory(documentId)
                val emptyShard = DocumentIndexShard(documentId, null, null, null, emptyList())
                indexShards.put(documentId, emptyShard)?.close()
                emptyShard
            } else {
                val shard = buildShard(
                    documentId,
                    contents,
                    forceRewrite = forceRebuild || !pageResult.fromCache,
                )
                indexShards.put(documentId, shard)?.close()
                shard
            }
        }
    }

    private fun applyActiveShard(documentId: String, shard: DocumentIndexShard) {
        currentDocumentId = documentId
        indexSearcher = shard.searcher
        pageContents = shard.contents
    }

    private fun buildShard(
        documentId: String,
        contents: List<PageSearchContent>,
        forceRewrite: Boolean,
    ): DocumentIndexShard {
        val indexDir = documentIndexDirectory(documentId) ?: return DocumentIndexShard(
            documentId,
            directory = null,
            reader = null,
            searcher = null,
            contents = contents
        )
        val luceneDirectory = runCatching { FSDirectory.open(indexDir.toPath()) }
            .onFailure { error ->
                NovaLog.w(TAG, "Unable to open Lucene directory at ${indexDir.absolutePath}", error)
            }
            .getOrNull()
        if (luceneDirectory == null) {
            deleteIndexDirectory(documentId)
            return DocumentIndexShard(documentId, null, null, null, contents)
        }
        var reader: DirectoryReader? = null
        try {
            var needsRewrite = forceRewrite || !DirectoryReader.indexExists(luceneDirectory)
            if (!needsRewrite) {
                DirectoryReader.open(luceneDirectory).use { existing ->
                    if (existing.numDocs() != contents.size) {
                        needsRewrite = true
                    }
                }
            }
            if (needsRewrite) {
                emitIndexingState(
                    documentId,
                    progress = INDEX_BASE,
                    phase = SearchIndexingPhase.WRITING_INDEX,
                )
                rewriteIndex(documentId, luceneDirectory, contents)
            } else {
                emitIndexingState(
                    documentId,
                    progress = stageProgress(INDEX_BASE, INDEX_WEIGHT, 1f),
                    phase = SearchIndexingPhase.WRITING_INDEX,
                )
            }
            reader = DirectoryReader.open(luceneDirectory)
            val searcher = IndexSearcher(reader)
            return DocumentIndexShard(documentId, luceneDirectory, reader, searcher, contents)
        } catch (error: Throwable) {
            NovaLog.w(TAG, "Unable to build Lucene index at ${indexDir.absolutePath}", error)
            runCatching { reader?.close() }
            runCatching { luceneDirectory.close() }
            deleteIndexDirectory(documentId)
            return DocumentIndexShard(documentId, null, null, null, contents)
        }
    }

    private fun rewriteIndex(
        documentId: String,
        directory: Directory,
        contents: List<PageSearchContent>,
    ) {
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE
        }
        IndexWriter(directory, config).use { writer ->
            if (contents.isEmpty()) {
                emitIndexingState(
                    documentId,
                    progress = stageProgress(INDEX_BASE, INDEX_WEIGHT, 1f),
                    phase = SearchIndexingPhase.WRITING_INDEX,
                )
            }
            contents.forEachIndexed { index, content ->
                val cleaned = content.text.trim()
                val document = Document().apply {
                    add(StoredField("page", index))
                    add(TextField("content", cleaned, Field.Store.YES))
                }
                writer.addDocument(document)
                val progress = stageProgress(
                    base = INDEX_BASE,
                    weight = INDEX_WEIGHT,
                    stageProgress = (index + 1).toFloat() / contents.size,
                )
                emitIndexingState(
                    documentId,
                    progress = progress,
                    phase = SearchIndexingPhase.WRITING_INDEX,
                )
            }
            writer.commit()
        }
    }

    private fun encodeDocumentId(documentId: String): String {
        return Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private suspend fun recognizeText(bitmap: Bitmap): OcrPageResult = suspendCancellableCoroutine { continuation ->
        val recognizer = textRecognizer
        if (recognizer == null) {
            continuation.resume(
                OcrPageResult(
                    text = "",
                    lines = emptyList(),
                    fallbackRegions = detectTextRegions(bitmap),
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height
                )
            )
            return@suspendCancellableCoroutine
        }
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                val text = result.text.trim()
                val lines = mutableListOf<OcrLine>()
                result.textBlocks.forEach { block ->
                    block.lines.forEach { line ->
                        val rect = line.boundingBox
                        if (!line.text.isNullOrBlank() && rect != null) {
                            lines += OcrLine(line.text, Rect(rect))
                        }
                    }
                }
                val fallback = if (lines.isEmpty()) detectTextRegions(bitmap) else emptyList()
                continuation.resume(
                    OcrPageResult(
                        text = text,
                        lines = lines,
                        fallbackRegions = fallback,
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height
                    )
                )
            }
            .addOnFailureListener { error ->
                NovaLog.w(TAG, "ML Kit text recognition failed", error)
                if (continuation.isActive) {
                    continuation.resume(
                        OcrPageResult(
                            text = "",
                            lines = emptyList(),
                            fallbackRegions = detectTextRegions(bitmap),
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height
                        )
                    )
                }
            }
            .addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume(
                        OcrPageResult(
                            text = "",
                            lines = emptyList(),
                            fallbackRegions = detectTextRegions(bitmap),
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height
                        )
                    )
                }
            }
    }

    private fun buildQuery(rawQuery: String): Query {
        val trimmed = rawQuery.trim()
        val parser = QueryParser("content", analyzer).apply {
            defaultOperator = QueryParser.Operator.AND
        }
        val escaped = QueryParser.escape(trimmed)
        val expression = if (trimmed.contains(' ')) {
            "\"$escaped\""
        } else {
            escaped
        }
        return try {
            parser.parse(expression)
        } catch (parse: ParseException) {
            TermQuery(Term("content", escaped.lowercase(Locale.US)))
        } catch (illegal: IllegalArgumentException) {
            TermQuery(Term("content", escaped.lowercase(Locale.US)))
        }
    }

    private fun countOccurrences(normalizedText: String, normalizedQuery: String): Int {
        if (normalizedText.isBlank() || normalizedQuery.isBlank()) return 0
        var index = normalizedText.indexOf(normalizedQuery)
        var count = 0
        while (index >= 0) {
            count++
            index = normalizedText.indexOf(normalizedQuery, index + normalizedQuery.length)
        }
        return count
    }
}
