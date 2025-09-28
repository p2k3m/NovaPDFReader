package com.novapdf.reader.search

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchMatch
import com.novapdf.reader.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.apache.lucene.store.RAMDirectory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume

private const val TAG = "LuceneSearchCoordinator"
private const val OCR_RENDER_TARGET_WIDTH = 1280
private val WHOLE_PAGE_RECT = RectSnapshot(0f, 0f, 1f, 1f)

class LuceneSearchCoordinator(
    private val context: Context,
    private val pdfRepository: PdfDocumentRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val analyzer = StandardAnalyzer()
    private val indexLock = Mutex()
    private var directory: RAMDirectory? = null
    private var indexSearcher: IndexSearcher? = null
    private var indexReader: DirectoryReader? = null
    private var currentDocumentId: String? = null
    private var prepareJob: Job? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun prepare(session: PdfDocumentSession) {
        prepareJob?.cancel()
        prepareJob = scope.launch {
            runCatching { rebuildIndex(session) }
                .onFailure { Log.w(TAG, "Failed to pre-build index", it) }
        }
    }

    suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        ensureIndexReady(session)
        val searcher = indexSearcher ?: return emptyList()
        val luceneQuery = buildQuery(query)
        val maxDocs = max(1, searcher.indexReader.maxDoc)
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
            val storedText = doc.get("content").orEmpty()
            val matchCount = max(1, countOccurrences(storedText, query))
            val matches = resultsByPage.getOrPut(pageIndex) { mutableListOf() }
            val startIndex = matches.size
            repeat(matchCount) { offset ->
                matches += SearchMatch(
                    indexInPage = startIndex + offset,
                    boundingBoxes = listOf(WHOLE_PAGE_RECT)
                )
            }
        }
        return resultsByPage.entries
            .sortedBy { it.key }
            .map { (pageIndex, matches) -> SearchResult(pageIndex, matches) }
    }

    fun dispose() {
        prepareJob?.cancel()
        prepareJob = null
        scope.cancel()
        runCatching { textRecognizer.close() }
        runCatching { indexReader?.close() }
        runCatching { directory?.close() }
        indexReader = null
        directory = null
        indexSearcher = null
        currentDocumentId = null
    }

    private suspend fun ensureIndexReady(session: PdfDocumentSession) {
        prepareJob?.let { job -> if (job.isActive) job.join() }
        if (currentDocumentId == session.documentId && indexSearcher != null) {
            return
        }
        rebuildIndex(session)
    }

    private suspend fun rebuildIndex(session: PdfDocumentSession) {
        val pageTexts = if (session.pageCount > 0) extractPageTexts(session) else emptyList()
        indexLock.withLock {
            runCatching { indexReader?.close() }
            indexReader = null
            directory?.close()
            if (pageTexts.isEmpty()) {
                directory = null
                indexSearcher = null
                currentDocumentId = session.documentId
                return@withLock
            }
            val ramDirectory = RAMDirectory()
            directory = ramDirectory
            val config = IndexWriterConfig(analyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE
            }
            IndexWriter(ramDirectory, config).use { writer ->
                pageTexts.forEachIndexed { index, text ->
                    val cleaned = text.trim()
                    val document = Document().apply {
                        add(StoredField("page", index))
                        add(TextField("content", cleaned, Field.Store.YES))
                    }
                    writer.addDocument(document)
                }
                writer.commit()
            }
            indexReader = DirectoryReader.open(ramDirectory)
            indexSearcher = indexReader?.let { IndexSearcher(it) }
            currentDocumentId = session.documentId
        }
    }

    private suspend fun extractPageTexts(session: PdfDocumentSession): List<String> = withContext(Dispatchers.IO) {
        val pageCount = session.pageCount
        val texts = MutableList(pageCount) { "" }
        try {
            context.contentResolver.openInputStream(session.uri)?.use { input ->
                PDDocument.load(input).use { document ->
                    val stripper = object : PDFTextStripper() {}
                    for (page in 1..pageCount) {
                        ensureActive()
                        stripper.startPage = page
                        stripper.endPage = page
                        val text = stripper.getText(document).trim()
                        texts[page - 1] = text
                    }
                }
            }
        } catch (io: IOException) {
            Log.w(TAG, "Failed to extract text with PDFBox", io)
        }
        texts.forEachIndexed { index, value ->
            if (value.isBlank()) {
                val recognized = runCatching { performOcr(index) }
                    .onFailure { Log.w(TAG, "OCR fallback failed for page $index", it) }
                    .getOrDefault("")
                if (recognized.isNotBlank()) {
                    texts[index] = recognized
                }
            }
        }
        texts
    }

    private suspend fun performOcr(pageIndex: Int): String = withContext(Dispatchers.IO) {
        val bitmap = pdfRepository.renderPage(pageIndex, OCR_RENDER_TARGET_WIDTH) ?: return@withContext ""
        try {
            recognizeText(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text?.trim().orEmpty()
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "ML Kit text recognition failed", error)
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
            .addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.resume("")
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

    private fun countOccurrences(text: String, rawQuery: String): Int {
        if (text.isBlank() || rawQuery.isBlank()) return 0
        val haystack = text.lowercase(Locale.US)
        val needle = rawQuery.lowercase(Locale.US).trim()
        if (needle.isEmpty()) return 0
        var index = haystack.indexOf(needle)
        var count = 0
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
