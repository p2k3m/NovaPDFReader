package com.novapdf.reader.search

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import org.apache.lucene.store.ByteBuffersDirectory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.StringWriter
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.jvm.Volatile

private const val TAG = "LuceneSearchCoordinator"
private const val OCR_RENDER_TARGET_WIDTH = 1280
private const val MAX_INDEXED_PAGE_COUNT = 400
private val WHOLE_PAGE_RECT = RectSnapshot(0f, 0f, 1f, 1f)

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

private const val INDEX_POOL_PARALLELISM = 1

class LuceneSearchCoordinator(
    private val context: Context,
    private val pdfRepository: PdfDocumentRepository,
    private val dispatchers: CoroutineDispatchers,
    scopeProvider: (CoroutineDispatcher) -> CoroutineScope = { dispatcher ->
        CoroutineScope(SupervisorJob() + dispatcher)
    }
) : DocumentSearchCoordinator {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val indexDispatcher = dispatchers.io.limitedParallelism(INDEX_POOL_PARALLELISM)

    private val scope = scopeProvider(indexDispatcher)
    private val analyzer = StandardAnalyzer()
    private val indexLock = Mutex()
    private var directory: ByteBuffersDirectory? = null
    private var indexSearcher: IndexSearcher? = null
    private var indexReader: DirectoryReader? = null
    private var currentDocumentId: String? = null
    private var prepareJob: Job? = null
    private val textRecognizer: TextRecognizer? = runCatching {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }.onFailure { error ->
        Log.w(TAG, "Text recognition unavailable; OCR fallback disabled", error)
    }.getOrNull()
    @Volatile
    private var pageContents: List<PageSearchContent> = emptyList()

    override fun prepare(session: PdfDocumentSession): Job? {
        prepareJob?.cancel()
        if (session.pageCount > MAX_INDEXED_PAGE_COUNT) {
            Log.i(
                TAG,
                "Skipping Lucene index preparation for large document " +
                    "(${session.pageCount} pages)"
            )
            currentDocumentId = session.documentId
            directory = null
            indexSearcher = null
            indexReader = null
            pageContents = emptyList()
            prepareJob = null
            return null
        }
        prepareJob = scope.launch {
            runCatching { rebuildIndex(session) }
                .onFailure { Log.w(TAG, "Failed to pre-build index", it) }
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
            Log.w(TAG, "Lucene search failed", parse)
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
        runCatching { indexReader?.close() }
        runCatching { directory?.close() }
        indexReader = null
        directory = null
        indexSearcher = null
        currentDocumentId = null
        pageContents = emptyList()
    }

    private suspend fun ensureIndexReady(session: PdfDocumentSession) {
        prepareJob?.let { job -> if (job.isActive) job.join() }
        if (currentDocumentId == session.documentId && indexSearcher != null) {
            return
        }
        if (session.pageCount > MAX_INDEXED_PAGE_COUNT) {
            Log.i(
                TAG,
                "Skipping Lucene index rebuild for large document (${session.pageCount} pages)"
            )
            currentDocumentId = session.documentId
            directory = null
            indexSearcher = null
            indexReader = null
            pageContents = emptyList()
            return
        }
        rebuildIndex(session)
    }

    private suspend fun rebuildIndex(session: PdfDocumentSession) {
        val contents = if (session.pageCount > 0) extractPageContent(session) else emptyList()
        indexLock.withLock {
            runCatching { indexReader?.close() }
            indexReader = null
            directory?.close()
            if (contents.isEmpty()) {
                directory = null
                indexSearcher = null
                currentDocumentId = session.documentId
                pageContents = emptyList()
                return@withLock
            }
            val memoryDirectory = ByteBuffersDirectory()
            directory = memoryDirectory
            val config = IndexWriterConfig(analyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE
            }
            IndexWriter(memoryDirectory, config).use { writer ->
                contents.forEachIndexed { index, content ->
                    val cleaned = content.text.trim()
                    val document = Document().apply {
                        add(StoredField("page", index))
                        add(TextField("content", cleaned, Field.Store.YES))
                    }
                    writer.addDocument(document)
                }
                writer.commit()
            }
            indexReader = DirectoryReader.open(memoryDirectory)
            indexSearcher = indexReader?.let { IndexSearcher(it) }
            currentDocumentId = session.documentId
            pageContents = contents
        }
    }

    private suspend fun extractPageContent(session: PdfDocumentSession): List<PageSearchContent> = withContext(indexDispatcher) {
        val pageCount = session.pageCount
        if (pageCount <= 0) return@withContext emptyList()
        val pdfBoxReady = PdfBoxInitializer.ensureInitialized(context)
        if (!pdfBoxReady) {
            Log.w(TAG, "PDFBox initialisation failed; skipping text extraction")
            return@withContext emptyList()
        }
        val contents = MutableList(pageCount) {
            PageSearchContent(
                text = "",
                normalizedText = "",
                runs = emptyList(),
                coordinateWidth = 1,
                coordinateHeight = 1,
                fallbackRegions = emptyList()
            )
        }
        try {
            context.contentResolver.openInputStream(session.uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    for (page in 0 until pageCount) {
                        ensureActive()
                        val pdPage = runCatching { document.getPage(page) }
                            .onFailure { Log.w(TAG, "Failed to read PDF page $page", it) }
                            .getOrNull() ?: continue
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
                            fallbackRegions = emptyList()
                        )
                    }
                }
            }
        } catch (io: IOException) {
            Log.w(TAG, "Failed to extract text with PDFBox", io)
        }
        contents.forEachIndexed { index, content ->
            val needsOcrForText = content.text.isBlank()
            val needsOcrForBounds = content.runs.isEmpty()
            if (!needsOcrForText && !needsOcrForBounds) {
                return@forEachIndexed
            }
            val fallback = runCatching {
                performOcr(index, content.coordinateWidth, content.coordinateHeight)
            }.onFailure {
                Log.w(TAG, "OCR fallback failed for page $index", it)
            }.getOrNull()
            if (fallback != null) {
                contents[index] = when {
                    needsOcrForText -> fallback
                    else -> content.copy(
                        runs = fallback.runs.takeIf { it.isNotEmpty() } ?: content.runs,
                        fallbackRegions = if (fallback.fallbackRegions.isNotEmpty()) {
                            fallback.fallbackRegions
                        } else {
                            content.fallbackRegions
                        }
                    )
                }
            }
        }
        contents
    }

    private suspend fun performOcr(
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): PageSearchContent? = withContext(indexDispatcher) {
        val bitmap = pdfRepository.renderPage(pageIndex, OCR_RENDER_TARGET_WIDTH) ?: return@withContext null
        try {
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
                Log.w(TAG, "ML Kit text recognition failed", error)
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
