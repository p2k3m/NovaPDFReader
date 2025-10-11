package com.novapdf.reader.data

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import android.util.LruCache
import android.util.Size
import android.util.SparseArray
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.novapdf.reader.cache.PdfCacheRoot
import com.novapdf.reader.logging.LogContext
import com.novapdf.reader.logging.LogField
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import com.novapdf.reader.model.PdfOutlineNode
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.LazyThreadSafetyMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.io.buffered
import kotlin.io.copyTo
import kotlin.text.Charsets
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.search.PdfBoxInitializer
import com.tom_roush.pdfbox.pdmodel.PDDocument

private const val CACHE_BUDGET_BYTES = 50L * 1024L * 1024L
private const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L
private const val MAX_BITMAP_DIMENSION = 8_192
private const val PRE_REPAIR_MIN_SIZE_BYTES = 2L * 1024L * 1024L
private const val PRE_REPAIR_SCAN_LIMIT_BYTES = 8L * 1024L * 1024L
private const val PRE_REPAIR_MAX_KIDS_PER_ARRAY = 32
private const val TAG = "PdfDocumentRepository"
private const val BITMAP_POOL_REPORT_INTERVAL = 64
private const val MAX_RENDER_FAILURES = 2
private const val CONTENT_RESOLVER_READ_TIMEOUT_MS = 30_000L
private const val REMOTE_CONNECT_TIMEOUT_MS = 15_000
private const val REMOTE_READ_TIMEOUT_MS = 30_000
private const val PDF_MAGIC_SCAN_LIMIT = 16
private const val LONG_OPERATION_THRESHOLD_MS = 16L
private const val TRACE_SECTION_PREFIX = "PdfRepo#"
private const val MAX_TRACE_SECTION_LENGTH = 127
private const val PERSISTENT_REPAIR_PREFIX = "cached-"
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

class PdfOpenException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        UNSUPPORTED,
        ACCESS_DENIED,
        CORRUPTED,
    }
}

class PdfRenderException(
    val reason: Reason,
    val suggestedWidth: Int? = null,
    val suggestedScale: Float? = null,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        PAGE_TOO_LARGE,
        MALFORMED_PAGE,
    }
}

data class PdfDocumentSession(
    val documentId: String,
    val uri: Uri,
    val pageCount: Int,
    val document: PdfDocument,
    val fileDescriptor: ParcelFileDescriptor
)

data class PageTileRequest(
    val pageIndex: Int,
    val tileRect: Rect,
    val scale: Float,
    val cancellationSignal: CancellationSignal? = null,
)

private data class PageBitmapKey(
    val pageIndex: Int,
    val width: Int,
    val profile: PageRenderProfile,
)

class PdfDocumentRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val crashReporter: CrashReporter? = null
) {
    private val appContext: Context = context.applicationContext
    private val contentResolver: ContentResolver = context.contentResolver
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pdfDispatcher: CoroutineDispatcher = ioDispatcher.limitedParallelism(1)
    private val renderScope = CoroutineScope(Job() + pdfDispatcher)
    private val repairedDocumentDir by lazy {
        File(PdfCacheRoot.documents(appContext), "repairs").apply {
            ensureCacheDirectory("PDF repair cache")
        }
    }
    private val remoteDocumentDir by lazy {
        File(PdfCacheRoot.documents(appContext), "remote").apply {
            ensureCacheDirectory("remote PDF cache")
        }
    }
    private val cacheLock = Mutex()
    private val maxCacheBytes = calculateCacheBudget()
    private val bitmapPoolMaxBytes = (maxCacheBytes / 2).coerceAtLeast(0L)
    private val bitmapMemoryBudgetBytes = maxCacheBytes + bitmapPoolMaxBytes
    private val bitmapMemoryWarningThreshold = if (bitmapMemoryBudgetBytes > 0L) {
        (bitmapMemoryBudgetBytes * 3L) / 4L
    } else {
        0L
    }
    private val bitmapMemoryCriticalThreshold = if (bitmapMemoryBudgetBytes > 0L) {
        (bitmapMemoryBudgetBytes * 9L) / 10L
    } else {
        0L
    }
    private val pdfiumCore = PdfiumCore(appContext)
    private val pdfiumCallVerifier = PdfiumCallVerifier()
    private val pdfiumPagesField by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            PdfDocument::class.java.getDeclaredField("mNativePagesPtr").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
    private val pdfiumClosePageMethod by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            PdfiumCore::class.java.getDeclaredMethod("nativeClosePage", java.lang.Long.TYPE).apply {
                isAccessible = true
            }
        }.getOrNull()
    }
    private val pdfiumLockObject by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            PdfiumCore::class.java.getDeclaredField("lock").apply {
                isAccessible = true
            }.get(null)
        }.getOrNull()
    }
    private val bitmapCache = createBitmapCache()
    private val bitmapPool = BitmapPool(
        maxBytes = bitmapPoolMaxBytes,
        reportInterval = BITMAP_POOL_REPORT_INTERVAL,
        onSnapshot = ::reportBitmapPoolMetrics,
    )
    private val _bitmapMemoryStats = MutableStateFlow(
        BitmapMemoryStats(
            currentBytes = 0L,
            peakBytes = 0L,
            warnThresholdBytes = bitmapMemoryWarningThreshold,
            criticalThresholdBytes = bitmapMemoryCriticalThreshold,
            level = BitmapMemoryLevel.NORMAL,
        )
    )
    val bitmapMemory: StateFlow<BitmapMemoryStats> = _bitmapMemoryStats.asStateFlow()
    private val bitmapMemoryTracker = BitmapMemoryTracker(
        warnThresholdBytes = bitmapMemoryWarningThreshold,
        criticalThresholdBytes = bitmapMemoryCriticalThreshold,
    )
    private val pageSizeCache = SparseArray<Size>()
    private val pageSizeLock = Mutex()
    private val openSession = MutableStateFlow<PdfDocumentSession?>(null)
    val session: StateFlow<PdfDocumentSession?> = openSession.asStateFlow()
    private val outlineNodes = MutableStateFlow<List<PdfOutlineNode>>(emptyList())
    val outline: StateFlow<List<PdfOutlineNode>> = outlineNodes.asStateFlow()
    private val renderProgressState = MutableStateFlow<PdfRenderProgress>(PdfRenderProgress.Idle)
    val renderProgress: StateFlow<PdfRenderProgress> = renderProgressState.asStateFlow()
    private val renderMutex = Mutex()

    private fun logFields(
        operation: String,
        documentId: String? = openSession.value?.documentId,
        pageIndex: Int? = null,
        sizeBytes: Long? = null,
        durationMs: Long? = null,
        vararg extras: LogField,
    ): Array<LogField> {
        return LogContext(
            module = LOG_MODULE,
            operation = operation,
            documentId = documentId,
            pageIndex = pageIndex,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
        ).fields(*extras)
    }

    private suspend fun <T> traceOperation(
        operation: String,
        documentId: String? = openSession.value?.documentId,
        pageIndex: Int? = null,
        sizeBytesProvider: () -> Long? = { null },
        block: suspend () -> T,
    ): T {
        val traceName = buildTraceSectionName(operation, pageIndex)
        Trace.beginSection(traceName)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        var failure: Throwable? = null
        return try {
            block()
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000
            Trace.endSection()
            val status = if (failure == null) "success" else "failure"
            if (elapsedMs >= LONG_OPERATION_THRESHOLD_MS) {
                val sizeBytes = runCatching(sizeBytesProvider).getOrNull()
                val baseContext = LogContext(
                    module = LOG_MODULE,
                    operation = operation,
                    documentId = documentId,
                    pageIndex = pageIndex,
                    sizeBytes = sizeBytes,
                )
                NovaLog.d(TAG, "Begin $operation", *baseContext.fields(field("event", "begin")))
                NovaLog.d(
                    TAG,
                    "End $operation",
                    *baseContext.copy(durationMs = elapsedMs).fields(
                        field("event", "end"),
                        field("status", status),
                    ),
                )
            }
        }
    }

    private fun buildTraceSectionName(operation: String, pageIndex: Int?): String {
        val suffix = pageIndex?.let { "#$it" } ?: ""
        val raw = "$TRACE_SECTION_PREFIX$operation$suffix"
        return if (raw.length <= MAX_TRACE_SECTION_LENGTH) raw else raw.substring(0, MAX_TRACE_SECTION_LENGTH)
    }
    private val malformedPages = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val pageFailureCounts = ConcurrentHashMap<Int, Int>()
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
            scheduleCacheClear()
        }

        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            when {
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> scheduleCacheTrim(0.5f)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> scheduleCacheClear()
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> scheduleCacheTrim(0.5f)
            }
        }
    }

    init {
        PdfCacheRoot.ensureSubdirectories(appContext)
    }

    private var repairedDocumentFile: File? = null

    private fun File.ensureCacheDirectory(label: String) {
        if (!exists() && !mkdirs()) {
            NovaLog.w(TAG, "Unable to create $label at ${absolutePath}")
        } else if (exists() && !isDirectory) {
            NovaLog.w(TAG, "$label path is not a directory: ${absolutePath}")
        }
    }

    private fun isInCacheDirectory(path: String?, directory: File): Boolean {
        if (path.isNullOrBlank()) return false
        return File(path).absolutePath.startsWith(directory.absolutePath)
    }
    private var repairedDocumentIsPersistent: Boolean = false
    private var cachedRemoteFile: File? = null

    init {
        appContext.registerComponentCallbacks(componentCallbacks)
    }

    @WorkerThread
    suspend fun open(uri: Uri, cancellationSignal: CancellationSignal? = null): PdfDocumentSession {
        return withContextGuard {
            cancellationSignal.throwIfCanceled()
            closeSessionInternal()
            releaseRepairedDocument(retainPersistent = true)
            cachedRemoteFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    NovaLog.w(TAG, "Unable to delete previous remote PDF cache at ${file.absolutePath}")
                }
            }
            cachedRemoteFile = null
            cancellationSignal.throwIfCanceled()
            var remoteCacheFile: File? = null
            remoteCacheFile = try {
                cacheRemoteUriIfNeeded(uri, cancellationSignal)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NovaLog.w(
                    TAG,
                    "Unable to cache remote PDF from $uri",
                    throwable = error,
                    *logFields(
                        operation = "cacheRemote",
                        documentId = uri.toString(),
                        pageIndex = null,
                        sizeBytes = null,
                        durationMs = null,
                        field("uri", uri),
                    ),
                )
                reportNonFatal(
                    error,
                    mapOf(
                        "stage" to "cacheRemote",
                        "uri" to uri.toString()
                    )
                )
                throw PdfOpenException(PdfOpenException.Reason.ACCESS_DENIED, error)
            }
            var keepRemoteCache = false
            var remoteCacheDeleted = false
            try {
                val candidateUri = remoteCacheFile?.toUri() ?: uri
                if (!validateDocumentUri(candidateUri)) {
                    throw PdfOpenException(PdfOpenException.Reason.UNSUPPORTED)
                }
                val preparedFile = prepareLargeDocumentIfNeeded(candidateUri, uri, cancellationSignal)
                if (preparedFile != null) {
                    assignRepairedDocument(preparedFile)
                }
                val sourceUri = repairedDocumentFile?.toUri() ?: candidateUri
                var activePfd = try {
                    openParcelFileDescriptor(sourceUri, cancellationSignal)
                } catch (security: SecurityException) {
                    reportNonFatal(
                        security,
                        mapOf(
                            "stage" to "openFileDescriptor",
                            "uri" to sourceUri.toString()
                        )
                    )
                    throw PdfOpenException(PdfOpenException.Reason.ACCESS_DENIED, security)
                } ?: throw PdfOpenException(PdfOpenException.Reason.ACCESS_DENIED)
                cancellationSignal.throwIfCanceled()
                val document: PdfDocument = try {
                    pdfiumCore.newDocument(activePfd)
                } catch (throwable: Throwable) {
                    try {
                        activePfd.close()
                    } catch (_: IOException) {
                    }
                    NovaLog.e(TAG, "Failed to open PDF via Pdfium", throwable)
                    reportNonFatal(
                        throwable,
                        mapOf(
                            "stage" to "pdfiumNewDocument",
                            "uri" to sourceUri.toString()
                        )
                    )
                    val repaired = attemptPdfRepair(candidateUri, cacheKey = uri)
                        ?: throw PdfOpenException(PdfOpenException.Reason.CORRUPTED, throwable)

                    val repairedPfd = try {
                        ParcelFileDescriptor.open(repaired, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (error: Exception) {
                        throw PdfOpenException(PdfOpenException.Reason.CORRUPTED, error)
                    }

                    val repairedDocument = try {
                        pdfiumCore.newDocument(repairedPfd)
                    } catch (second: Throwable) {
                        try {
                            repairedPfd.close()
                        } catch (_: IOException) {
                        }
                        NovaLog.e(TAG, "Failed to open repaired PDF via Pdfium", second)
                        reportNonFatal(
                            second,
                            mapOf(
                                "stage" to "pdfiumNewDocumentRepair",
                                "uri" to candidateUri.toString()
                            )
                        )
                        throw PdfOpenException(PdfOpenException.Reason.CORRUPTED, second)
                    }

                    if (repairedDocumentFile != null && repairedDocumentFile != repaired) {
                        cleanedUpRepairedFile(repairedDocumentFile!!)
                    }
                    assignRepairedDocument(repaired)
                    activePfd = repairedPfd
                    repairedDocument
                }
                val pageCount = withPdfiumDocument(document, sourceUri) {
                    pdfiumCore.getPageCount(document)
                }
                val session = PdfDocumentSession(
                    documentId = uri.toString(),
                    uri = sourceUri,
                    pageCount = pageCount,
                    document = document,
                    fileDescriptor = activePfd
                )
                openSession.value = session
                outlineNodes.value = parseDocumentOutline(session)
                if (repairedDocumentFile == null) {
                    cachedRemoteFile = remoteCacheFile
                    keepRemoteCache = remoteCacheFile != null
                } else if (remoteCacheFile != null && remoteCacheFile != repairedDocumentFile) {
                    if (remoteCacheFile.exists() && !remoteCacheFile.delete()) {
                        NovaLog.w(TAG, "Unable to delete temporary remote PDF at ${remoteCacheFile.absolutePath}")
                    }
                    remoteCacheDeleted = true
                }
                session
            } finally {
                if (!keepRemoteCache && !remoteCacheDeleted) {
                    remoteCacheFile?.let { file ->
                        if (file.exists() && !file.delete()) {
                            NovaLog.w(TAG, "Unable to delete remote PDF cache at ${file.absolutePath}")
                        }
                    }
                }
            }
        }
    }

    @WorkerThread
    suspend fun getPageSize(pageIndex: Int): Size? = withContextGuard {
        getPageSizeInternal(pageIndex)
    }

    @WorkerThread
    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        profile: PageRenderProfile = PageRenderProfile.HIGH_DETAIL,
        cancellationSignal: CancellationSignal? = null,
    ): Bitmap? = withContextGuard {
        cancellationSignal.throwIfCanceled()
        if (targetWidth <= 0) return@withContextGuard null
        if (malformedPages.contains(pageIndex)) {
            throw PdfRenderException(PdfRenderException.Reason.MALFORMED_PAGE)
        }
        val bitmap = ensurePageBitmap(
            pageIndex,
            targetWidth,
            profile = profile,
            cancellationSignal = cancellationSignal,
        ) ?: return@withContextGuard null
        cancellationSignal.throwIfCanceled()
        copyBitmap(bitmap)
    }

    @WorkerThread
    suspend fun renderTile(
        pageIndex: Int,
        tileRect: Rect,
        scale: Float,
        cancellationSignal: CancellationSignal? = null,
    ): Bitmap? {
        return renderTile(PageTileRequest(pageIndex, tileRect, scale, cancellationSignal))
    }

    @WorkerThread
    suspend fun renderTile(request: PageTileRequest): Bitmap? = withContextGuard {
        request.cancellationSignal.throwIfCanceled()
        coroutineContext.ensureActive()
        if (malformedPages.contains(request.pageIndex)) {
            throw PdfRenderException(PdfRenderException.Reason.MALFORMED_PAGE)
        }
        if (openSession.value == null) return@withContextGuard null
        val pageSize = getPageSizeInternal(request.pageIndex) ?: return@withContextGuard null
        val pageWidth = pageSize.width
        val pageHeight = pageSize.height
        if (pageWidth <= 0 || pageHeight <= 0 || request.scale <= 0f) {
            return@withContextGuard null
        }

        val clampedLeft = request.tileRect.left.coerceIn(0, pageWidth - 1)
        val clampedTop = request.tileRect.top.coerceIn(0, pageHeight - 1)
        val clampedRight = request.tileRect.right.coerceIn(clampedLeft + 1, pageWidth)
        val clampedBottom = request.tileRect.bottom.coerceIn(clampedTop + 1, pageHeight)

        val tileWidth = ((clampedRight - clampedLeft) * request.scale).roundToInt().coerceAtLeast(1)
        val tileHeight = ((clampedBottom - clampedTop) * request.scale).roundToInt().coerceAtLeast(1)
        val tileSizeBytes = tileWidth.toLong() * tileHeight.toLong() * 4L
        val targetWidth = max(1, (pageWidth * request.scale).roundToInt())
        val targetHeight = max(1, (pageHeight * request.scale).roundToInt())
        val maxDimension = max(max(tileWidth, tileHeight), max(targetWidth, targetHeight))
        if (maxDimension > MAX_BITMAP_DIMENSION) {
            val adjustedScale = ((request.scale.toDouble() * MAX_BITMAP_DIMENSION.toDouble()) /
                (maxDimension.toDouble() + 1.0)).coerceAtLeast(0.01).toFloat()
            NovaLog.w(
                TAG,
                "Requested tile exceeds bitmap dimension cap ($tileWidth x $tileHeight); " +
                    "falling back to scale $adjustedScale"
            )
            throw PdfRenderException(
                PdfRenderException.Reason.PAGE_TOO_LARGE,
                suggestedScale = adjustedScale
            )
        }
        val offsetX = (-clampedLeft * request.scale).roundToInt()
        val offsetY = (-clampedTop * request.scale).roundToInt()

        val lockOwner = Any()
        renderMutex.lock(lockOwner)
        val activeSession = openSession.value
        if (activeSession == null) {
            renderMutex.unlock(lockOwner)
            return@withContextGuard null
        }
        val result = try {
            traceOperation(
                operation = "renderTile",
                documentId = activeSession.documentId,
                pageIndex = request.pageIndex,
                sizeBytesProvider = { tileSizeBytes },
            ) {
                request.cancellationSignal.throwIfCanceled()
                coroutineContext.ensureActive()
                val bitmap = obtainBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
                try {
                    request.cancellationSignal.throwIfCanceled()
                    coroutineContext.ensureActive()
                    withPdfiumPage(activeSession, request.pageIndex) {
                        pdfiumCore.renderPageBitmap(
                            activeSession.document,
                            bitmap,
                            request.pageIndex,
                            offsetX,
                            offsetY,
                            targetWidth,
                            targetHeight,
                            true
                        )
                    }
                    request.cancellationSignal.throwIfCanceled()
                    coroutineContext.ensureActive()
                    pageFailureCounts.remove(request.pageIndex)
                    bitmap
                } catch (throwable: Throwable) {
                    recycleBitmap(bitmap)
                    if (throwable is CancellationException) {
                        throw throwable
                    }
                    if (throwable is PdfRenderException) throw throwable
                    NovaLog.e(
                        TAG,
                        "Failed to render tile for page ${request.pageIndex}",
                        throwable = throwable,
                        *logFields(
                            operation = "renderTile",
                            documentId = activeSession.documentId,
                            pageIndex = request.pageIndex,
                            sizeBytes = tileSizeBytes,
                        ),
                    )
                    val failureCount = pageFailureCounts.merge(request.pageIndex, 1) { previous, increment ->
                        previous + increment
                    } ?: 1
                    val isMalformed = throwable.isLikelyMalformedPageError()
                    val shouldMarkMalformed = isMalformed || failureCount >= MAX_RENDER_FAILURES
                    reportNonFatal(
                        throwable,
                        mapOf(
                            "stage" to "renderTile",
                            "pageIndex" to request.pageIndex.toString(),
                            "scale" to request.scale.toString(),
                            "tile" to "$clampedLeft,$clampedTop,$clampedRight,$clampedBottom",
                            "malformed" to shouldMarkMalformed.toString(),
                            "failures" to failureCount.toString()
                        )
                    )
                    if (shouldMarkMalformed) {
                        markPageMalformed(request.pageIndex)
                        throw PdfRenderException(
                            PdfRenderException.Reason.MALFORMED_PAGE,
                            cause = throwable
                        )
                    }
                    null
                }
            }
        } finally {
            renderMutex.unlock(lockOwner)
        }
        result
    }

    fun prefetchPages(indices: List<Int>, targetWidth: Int) {
        if (targetWidth <= 0) return
        val session = openSession.value ?: return
        if (indices.isEmpty()) return
        renderScope.launch {
            indices.forEach { index ->
                if (index in 0 until session.pageCount) {
                    if (malformedPages.contains(index)) return@forEach
                    // Avoid blocking foreground renders if decoding is already busy.
                    try {
                        ensurePageBitmap(
                            index,
                            targetWidth,
                            profile = PageRenderProfile.HIGH_DETAIL,
                            allowSkippingIfBusy = true,
                        )
                    } catch (render: PdfRenderException) {
                        when (render.reason) {
                            PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                                NovaLog.w(TAG, "Skipping oversized page prefetch for index $index")
                            }

                            PdfRenderException.Reason.MALFORMED_PAGE -> {
                                NovaLog.w(TAG, "Skipping malformed page prefetch for index $index")
                            }
                        }
                    }
                }
            }
        }
    }

    fun preloadTiles(indices: List<Int>, tileFractions: List<RectF>, scale: Float) {
        val session = openSession.value ?: return
        if (tileFractions.isEmpty() || scale <= 0f) return
        renderScope.launch {
            indices.forEach { pageIndex ->
                if (pageIndex in 0 until session.pageCount) {
                    if (malformedPages.contains(pageIndex)) return@forEach
                    val pageSize = getPageSizeInternal(pageIndex) ?: return@forEach
                    val requests = tileFractions
                        .map { fraction -> fraction.toPageRect(pageSize) }
                        .filter { rect -> rect.width() > 0 && rect.height() > 0 }
                        .map { rect -> PageTileRequest(pageIndex, rect, scale) }
                    requests.forEach { request ->
                        try {
                            renderTile(request)?.let { bitmap -> recycleBitmap(bitmap) }
                        } catch (render: PdfRenderException) {
                            when (render.reason) {
                                PdfRenderException.Reason.PAGE_TOO_LARGE -> {
                                    NovaLog.w(TAG, "Skipping oversized tile preload for page ${request.pageIndex}")
                                }

                                PdfRenderException.Reason.MALFORMED_PAGE -> {
                                    NovaLog.w(TAG, "Skipping malformed tile preload for page ${request.pageIndex}")
                                }
                            }
                        } catch (_: CancellationException) {
                            return@launch
                        }
                    }
                }
            }
        }
    }

    fun createPrintAdapter(context: Context): PrintDocumentAdapter? {
        val session = openSession.value ?: return null
        return object : PrintDocumentAdapter() {
            private var cancelled = false

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    cancelled = true
                    callback?.onLayoutCancelled()
                    return
                }
                val docInfo = PrintDocumentInfo.Builder(session.documentId.ifEmpty { "NovaPDF-document" })
                    .setPageCount(session.pageCount)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback?.onLayoutFinished(docInfo, true)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onWrite(
                pages: Array<PageRange>,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal,
                callback: WriteResultCallback
            ) {
                if (cancelled || cancellationSignal.isCanceled) {
                    callback.onWriteCancelled()
                    return
                }
                val resolver = context.contentResolver
                val input = resolver.openFileDescriptor(session.uri, "r")
                if (input == null) {
                    callback.onWriteFailed("Unable to open document")
                    return
                }
                input.use { source ->
                    try {
                        FileInputStream(source.fileDescriptor).buffered().use { inStream ->
                            FileOutputStream(destination.fileDescriptor).use { outStream ->
                                val buffer = ByteArray(DEFAULT_PRINT_BUFFER_SIZE)
                                while (true) {
                                    if (cancellationSignal.isCanceled) {
                                        callback.onWriteCancelled()
                                        return
                                    }
                                    val read = inStream.read(buffer)
                                    if (read == -1) break
                                    outStream.write(buffer, 0, read)
                                }
                                outStream.flush()
                            }
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (io: IOException) {
                        reportNonFatal(
                            io,
                            mapOf(
                                "stage" to "printWrite",
                                "uri" to session.uri.toString()
                            )
                        )
                        callback.onWriteFailed(io.localizedMessage ?: "Unable to export document")
                    }
                }
            }
        }
    }

    @WorkerThread
    private suspend fun <T> withContextGuard(block: suspend () -> T): T = withContext(pdfDispatcher) {
        ensureWorkerThread()
        block()
    }

    private fun validateDocumentUri(uri: Uri): Boolean {
        if (!isAllowedScheme(uri)) {
            NovaLog.w(TAG, "Rejected document with unsupported scheme: ${uri.scheme}")
            return false
        }

        val mimeType = resolveMimeType(uri)?.lowercase(Locale.US)
        if (mimeType != "application/pdf") {
            NovaLog.w(TAG, "Rejected document with unsupported MIME type: $mimeType")
            return false
        }

        val size = resolveDocumentSize(uri)
        if (size == null || size <= 0L) {
            NovaLog.w(TAG, "Rejected document with unknown or empty size")
            return false
        }
        if (size > MAX_DOCUMENT_BYTES) {
            NovaLog.w(TAG, "Rejected document exceeding size limit: $size")
            return false
        }

        if (!hasPdfMagicHeader(uri)) {
            NovaLog.w(TAG, "Rejected document without PDF magic header: $uri")
            return false
        }

        return true
    }

    private fun isAllowedScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        return scheme == ContentResolver.SCHEME_CONTENT || scheme == ContentResolver.SCHEME_FILE
    }

    private fun resolveMimeType(uri: Uri): String? {
        val reported = runCatching { contentResolver.getType(uri) }.getOrNull()
        val extension = when (uri.scheme?.lowercase(Locale.US)) {
            ContentResolver.SCHEME_FILE -> {
                val extensionFromUrl = MimeTypeMap
                    .getFileExtensionFromUrl(uri.toString())
                    ?.lowercase(Locale.US)
                when {
                    !extensionFromUrl.isNullOrEmpty() -> extensionFromUrl
                    else -> uri.path
                        ?.substringAfterLast('.', missingDelimiterValue = "")
                        ?.lowercase(Locale.US)
                }
            }
            else -> null
        }

        val normalized = normalizedMimeType(reported, extension)

        if (normalized == null && uri.scheme == ContentResolver.SCHEME_FILE) {
            if (!extension.isNullOrEmpty()) {
                NovaLog.w(TAG, "Unknown MIME type for file extension .$extension from $uri")
            } else {
                NovaLog.w(TAG, "Unable to resolve file extension for $uri")
            }
        } else if (
            normalized == "application/pdf" &&
            reported?.lowercase(Locale.US)?.contains("pdf") != true &&
            extension == "pdf"
        ) {
            NovaLog.i(TAG, "Falling back to manual PDF MIME type detection for $uri")
        }

        return normalized
    }

    private fun hasPdfMagicHeader(uri: Uri): Boolean {
        val inputStream = try {
            contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            NovaLog.w(TAG, "Unable to inspect header for $uri", error)
            return false
        }

        if (inputStream == null) {
            NovaLog.w(TAG, "Unable to obtain input stream for header inspection: $uri")
            return false
        }

        inputStream.use { stream ->
            val buffer = ByteArray(PDF_MAGIC_SCAN_LIMIT)
            var bytesRead = 0
            while (bytesRead < buffer.size) {
                val read = try {
                    stream.read(buffer, bytesRead, buffer.size - bytesRead)
                } catch (error: IOException) {
                    NovaLog.w(TAG, "Unable to read header bytes for $uri", error)
                    return false
                }
                if (read <= 0) {
                    break
                }
                bytesRead += read
            }

            return hasPdfMagic(buffer, bytesRead)
        }
    }


    private fun resolveDocumentSize(uri: Uri): Long? {
        return when (uri.scheme?.lowercase(Locale.US)) {
            ContentResolver.SCHEME_CONTENT ->
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && !cursor.isNull(index)) {
                            cursor.getLong(index)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            ContentResolver.SCHEME_FILE -> uri.path?.let { path -> File(path).takeIf { it.exists() }?.length() }
            else -> null
        }
    }

    fun dispose() {
        appContext.unregisterComponentCallbacks(componentCallbacks)
        renderScope.coroutineContext.cancelChildren()
        val cleanupJob = renderScope.launch {
            closeSessionInternal()
            cacheLock.withLock { clearBitmapCacheLocked() }
        }
        cleanupJob.invokeOnCompletion {
            renderScope.cancel()
        }
    }

    @WorkerThread
    private suspend fun getPageSizeInternal(pageIndex: Int): Size? {
        ensureWorkerThread()
        val session = openSession.value ?: return null
        return traceOperation(
            operation = "getPageSize",
            documentId = session.documentId,
            pageIndex = pageIndex,
        ) {
            pageSizeLock.withLock {
                pageSizeCache[pageIndex]?.let { return@withLock it }
                try {
                    withPdfiumPage(session, pageIndex) {
                        val width = pdfiumCore.getPageWidthPoint(session.document, pageIndex)
                        val height = pdfiumCore.getPageHeightPoint(session.document, pageIndex)
                        val size = Size(width, height)
                        pageSizeCache.put(pageIndex, size)
                        size
                    }
                } catch (throwable: Throwable) {
                    NovaLog.e(
                        TAG,
                        "Failed to obtain page size for index $pageIndex",
                        throwable = throwable,
                        *logFields(
                            operation = "getPageSize",
                            documentId = session.documentId,
                            pageIndex = pageIndex,
                        ),
                    )
                    reportNonFatal(
                        throwable,
                        mapOf(
                            "stage" to "pageSize",
                            "pageIndex" to pageIndex.toString()
                        )
                    )
                    null
                }
            }
        }
    }

    @WorkerThread
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ensurePageBitmap(
        pageIndex: Int,
        targetWidth: Int,
        profile: PageRenderProfile,
        allowSkippingIfBusy: Boolean = false,
        cancellationSignal: CancellationSignal? = null,
    ): Bitmap? {
        // When prefetching we avoid waiting on the render mutex so interactive draws remain responsive.
        ensureWorkerThread()
        cancellationSignal.throwIfCanceled()
        if (targetWidth <= 0) return null
        val session = openSession.value ?: return null
        val key = PageBitmapKey(pageIndex, targetWidth, profile)
        bitmapCache.get(key)?.let { existing ->
            if (!existing.isRecycled) {
                return existing
            }
        }

        val lockOwner = Any()
        val lockAcquired = if (allowSkippingIfBusy) {
            renderMutex.tryLock(lockOwner)
        } else {
            renderMutex.lock(lockOwner)
            true
        }
        cancellationSignal.throwIfCanceled()
        if (!lockAcquired) {
            return null
        }

        var estimatedBytes: Long? = null
        return try {
            traceOperation(
                operation = "renderPage",
                documentId = session.documentId,
                pageIndex = pageIndex,
                sizeBytesProvider = { estimatedBytes },
            ) {
                bitmapCache.get(key)?.let { cached ->
                    if (!cached.isRecycled) {
                        return@traceOperation cached
                    }
                }
                cancellationSignal.throwIfCanceled()
                val pageSize = getPageSizeInternal(pageIndex) ?: return@traceOperation null
                if (pageSize.width <= 0 || pageSize.height <= 0) {
                    return@traceOperation null
                }
                val aspect = pageSize.height.toDouble() / pageSize.width.toDouble()
                val targetHeight = max(1, (aspect * targetWidth.toDouble()).roundToInt())
                val maxDimension = max(targetWidth, targetHeight)
                if (maxDimension > MAX_BITMAP_DIMENSION) {
                    val suggestedWidth = ((targetWidth.toLong() * MAX_BITMAP_DIMENSION) / maxDimension)
                        .toInt().coerceAtLeast(1)
                    NovaLog.w(
                        TAG,
                        "Requested page exceeds bitmap dimension cap ($targetWidth x $targetHeight); " +
                            "suggesting width $suggestedWidth"
                    )
                    throw PdfRenderException(
                        PdfRenderException.Reason.PAGE_TOO_LARGE,
                        suggestedWidth = suggestedWidth
                    )
                }
                updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0f))
                try {
                    updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0.2f))
                    cancellationSignal.throwIfCanceled()
                    val config = bitmapConfigFor(profile)
                    estimatedBytes = targetWidth.toLong() * targetHeight.toLong() * bytesPerPixel(config).toLong()
                    val bitmap = obtainBitmap(targetWidth, targetHeight, config)
                    updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0.6f))
                    cancellationSignal.throwIfCanceled()
                    withPdfiumPage(session, pageIndex) {
                        pdfiumCore.renderPageBitmap(
                            session.document,
                            bitmap,
                            pageIndex,
                            0,
                            0,
                            targetWidth,
                            targetHeight,
                            true
                        )
                    }
                    updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 1f))
                    bitmapCache.put(key, bitmap)
                    pageFailureCounts.remove(pageIndex)
                    bitmap
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    if (throwable is PdfRenderException) throw throwable
                    NovaLog.e(
                        TAG,
                        "Failed to render page $pageIndex",
                        throwable = throwable,
                        *logFields(
                            operation = "renderPage",
                            documentId = session.documentId,
                            pageIndex = pageIndex,
                            sizeBytes = estimatedBytes,
                        ),
                    )
                    val failureCount = pageFailureCounts.merge(pageIndex, 1) { previous, increment ->
                        previous + increment
                    } ?: 1
                    val isMalformed = throwable.isLikelyMalformedPageError()
                    val shouldMarkMalformed = isMalformed || failureCount >= MAX_RENDER_FAILURES
                    reportNonFatal(
                        throwable,
                        mapOf(
                            "stage" to "renderPage",
                            "pageIndex" to pageIndex.toString(),
                            "targetWidth" to targetWidth.toString(),
                            "malformed" to shouldMarkMalformed.toString(),
                            "failures" to failureCount.toString()
                        )
                    )
                    if (shouldMarkMalformed) {
                        markPageMalformed(pageIndex)
                        throw PdfRenderException(
                            PdfRenderException.Reason.MALFORMED_PAGE,
                            cause = throwable
                        )
                    }
                    null
                } finally {
                    updateRenderProgress(PdfRenderProgress.Idle)
                }
            }
        } finally {
            renderMutex.unlock(lockOwner)
        }
    }

    private fun copyBitmap(source: Bitmap): Bitmap? {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        return try {
            source.copy(config, false).also(::recordBitmapAllocation)
        } catch (throwable: Throwable) {
            NovaLog.w(TAG, "Unable to copy bitmap", throwable)
            reportNonFatal(
                throwable,
                mapOf(
                    "stage" to "copyBitmap",
                    "config" to config.name
                )
            )
            null
        }
    }

    private fun RectF.toPageRect(size: Size): Rect {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            return Rect()
        }
        val left = (this.left * width).toInt().coerceIn(0, width)
        val top = (this.top * height).toInt().coerceIn(0, height)
        val right = (this.right * width).roundToInt().coerceIn(left + 1, width)
        val bottom = (this.bottom * height).roundToInt().coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun markPageMalformed(pageIndex: Int) {
        pageFailureCounts.remove(pageIndex)
        if (malformedPages.add(pageIndex)) {
            NovaLog.w(TAG, "Marking page $pageIndex as malformed; subsequent renders will be skipped")
        }
    }

    private fun Throwable.isLikelyMalformedPageError(): Boolean {
        if (this is OutOfMemoryError) return false
        val message = message?.lowercase(Locale.US).orEmpty()
        if (message.isNotEmpty()) {
            val keywords = listOf("malformed", "invalid", "corrupt", "corrupted", "parse", "stream")
            if (keywords.any { keyword -> keyword in message }) {
                return true
            }
            if ((this is IllegalArgumentException || this is IllegalStateException || this is RuntimeException) &&
                (message.contains("nativerenderpagebitmap") || message.contains("nativegetbitmap") || message.contains("nativeopenpage"))
            ) {
                return true
            }
        }
        val pdfiumFrame = stackTrace.firstOrNull { frame ->
            frame.className.contains("Pdfium", ignoreCase = true) ||
                frame.className.contains("PdfDocument", ignoreCase = true)
        }
        if (pdfiumFrame != null && (this is IllegalArgumentException || this is IllegalStateException)) {
            return true
        }
        val cause = cause ?: return false
        return cause.isLikelyMalformedPageError()
    }

    private fun ensureWorkerThread() {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null && Thread.currentThread() == mainLooper.thread) {
            throw IllegalStateException("PDF operations must not run on the main thread")
        }
    }

    @WorkerThread
    private suspend fun closeSessionInternal() {
        ensureWorkerThread()
        cacheLock.withLock { clearBitmapCacheLocked() }
        pageSizeCache.clear()
        outlineNodes.value = emptyList()
        updateRenderProgress(PdfRenderProgress.Idle)
        openSession.value?.let { session ->
            try {
                withPdfiumDocument(session.document, session.documentId) {
                    pdfiumCore.closeDocument(session.document)
                }
                session.fileDescriptor.close()
            } catch (_: IOException) {
            }
        }
        openSession.value = null
        malformedPages.clear()
        pageFailureCounts.clear()
        releaseRepairedDocument(retainPersistent = true)
        cachedRemoteFile?.let { file ->
            if (file.exists() && !file.delete()) {
                NovaLog.w(TAG, "Unable to delete cached remote PDF at ${file.absolutePath}")
            }
        }
        cachedRemoteFile = null
    }

    @WorkerThread
    private fun updateRenderProgress(progress: PdfRenderProgress) {
        ensureWorkerThread()
        renderProgressState.value = progress
    }

    private suspend fun prepareLargeDocumentIfNeeded(
        uri: Uri,
        originalUri: Uri,
        cancellationSignal: CancellationSignal? = null,
    ): File? {
        cancellationSignal.throwIfCanceled()
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            if (isInCacheDirectory(uri.path, repairedDocumentDir)) {
                return null
            }
        }

        val size = resolveDocumentSize(uri)
        if (size != null && size < PRE_REPAIR_MIN_SIZE_BYTES) {
            return null
        }

        cancellationSignal.throwIfCanceled()
        if (!detectOversizedPageTree(uri, size, cancellationSignal)) {
            return null
        }

        NovaLog.i(TAG, "Detected oversized page tree for $uri; attempting pre-emptive repair")
        cancellationSignal.throwIfCanceled()
        return attemptPdfRepair(uri, cacheKey = originalUri, cancellationSignal = cancellationSignal)
    }

    private suspend fun cacheRemoteUriIfNeeded(
        uri: Uri,
        cancellationSignal: CancellationSignal? = null,
    ): File? {
        var downloadedFile: File? = null
        return traceOperation(
            operation = "cacheRemote",
            documentId = uri.toString(),
            sizeBytesProvider = { downloadedFile?.takeIf(File::exists)?.length() },
        ) {
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@traceOperation null
            if (scheme == ContentResolver.SCHEME_FILE || scheme == ContentResolver.SCHEME_CONTENT) {
                return@traceOperation null
            }
            if (scheme != "http" && scheme != "https" && scheme != "s3") {
                return@traceOperation null
            }
            if (!remoteDocumentDir.isDirectory) {
                NovaLog.w(TAG, "Remote PDF cache directory unavailable at ${remoteDocumentDir.absolutePath}")
                return@traceOperation null
            }

            val destination = try {
                File.createTempFile("remote-", ".pdf", remoteDocumentDir)
            } catch (error: IOException) {
                NovaLog.w(TAG, "Unable to create remote PDF cache file", error)
                return@traceOperation null
            }

            try {
                withTimeout(CONTENT_RESOLVER_READ_TIMEOUT_MS) {
                    openRemoteInputStream(uri).use { input ->
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                cancellationSignal?.throwIfCanceled()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                }
                downloadedFile = destination
                destination
            } catch (cancelled: CancellationException) {
                if (destination.exists() && !destination.delete()) {
                    NovaLog.w(TAG, "Unable to delete cancelled remote PDF cache at ${destination.absolutePath}")
                }
                throw cancelled
            } catch (error: Exception) {
                if (destination.exists() && !destination.delete()) {
                    NovaLog.w(TAG, "Unable to delete failed remote PDF cache at ${destination.absolutePath}")
                } else {
                    destination.delete()
                }
                throw error
            }
        }
    }

    private fun openRemoteInputStream(uri: Uri): InputStream {
        val scheme = uri.scheme?.lowercase(Locale.US)
        return when (scheme) {
            "http", "https" -> {
                val url = URL(uri.toString())
                val connection = url.openConnection() as? HttpURLConnection
                    ?: throw IOException("Unsupported connection for $uri")
                connection.connectTimeout = REMOTE_CONNECT_TIMEOUT_MS
                connection.readTimeout = REMOTE_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.connect()
                val stream = connection.inputStream
                object : FilterInputStream(stream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            }

            "s3" -> contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open remote input stream for $uri")

            else -> throw IOException("Unsupported remote URI scheme: $scheme")
        }
    }

    private suspend fun detectOversizedPageTree(
        uri: Uri,
        sizeHint: Long?,
        cancellationSignal: CancellationSignal? = null,
    ): Boolean {
        val buffer = readBytesForPageTreeInspection(uri, sizeHint, cancellationSignal) ?: return false
        val contents = try {
            String(buffer, Charsets.ISO_8859_1)
        } catch (error: Exception) {
            NovaLog.w(TAG, "Unable to decode PDF for page tree inspection", error)
            return false
        }

        val matcher = KIDS_ARRAY_PATTERN.matcher(contents)
        while (matcher.find()) {
            cancellationSignal.throwIfCanceled()
            val section = matcher.group(1) ?: continue
            val referenceMatcher = KID_REFERENCE_PATTERN.matcher(section)
            var count = 0
            while (referenceMatcher.find()) {
                cancellationSignal.throwIfCanceled()
                count++
                if (count > PRE_REPAIR_MAX_KIDS_PER_ARRAY) {
                    NovaLog.w(TAG, "Detected oversized /Kids array with $count entries for $uri")
                    return true
                }
            }
        }
        return false
    }

    private suspend fun readBytesForPageTreeInspection(
        uri: Uri,
        sizeHint: Long?,
        cancellationSignal: CancellationSignal? = null,
    ): ByteArray? {
        val limit = when {
            sizeHint != null && sizeHint in 1L..PRE_REPAIR_SCAN_LIMIT_BYTES -> sizeHint.toInt()
            else -> PRE_REPAIR_SCAN_LIMIT_BYTES.toInt()
        }.coerceAtLeast(1)

        return try {
            contentResolver.openInputStream(uri)?.let { raw ->
                raw.buffered().use { stream ->
                    withTimeout(CONTENT_RESOLVER_READ_TIMEOUT_MS) {
                        val output = ByteArrayOutputStream(limit)
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var remaining = limit
                        while (remaining > 0) {
                            cancellationSignal.throwIfCanceled()
                            val read = stream.read(buffer, 0, min(buffer.size, remaining))
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                        if (output.size() == 0) {
                            null
                        } else {
                            output.toByteArray()
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            NovaLog.w(TAG, "Timed out while inspecting PDF for page tree analysis", timeout)
            null
        } catch (error: SecurityException) {
            NovaLog.w(TAG, "Unable to inspect PDF for page tree analysis", error)
            null
        } catch (error: IOException) {
            NovaLog.w(TAG, "I/O error while inspecting PDF for page tree analysis", error)
            null
        }
    }

    private fun openParcelFileDescriptor(
        uri: Uri,
        cancellationSignal: CancellationSignal? = null,
    ): ParcelFileDescriptor? {
        return when (uri.scheme?.lowercase(Locale.US)) {
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: return null
                val file = File(path)
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            else -> contentResolver.openFileDescriptor(uri, "r", cancellationSignal)
        }
    }

    private fun cleanedUpRepairedFile(file: File) {
        if (isPersistentRepairFile(file)) {
            return
        }
        if (file.exists() && !file.delete()) {
            NovaLog.w(TAG, "Unable to delete repaired PDF at ${file.absolutePath}")
        }
    }

    private fun assignRepairedDocument(file: File?) {
        repairedDocumentFile = file
        repairedDocumentIsPersistent = file?.let(::isPersistentRepairFile) ?: false
    }

    private fun releaseRepairedDocument(retainPersistent: Boolean) {
        val file = repairedDocumentFile
        if (file != null) {
            val shouldDelete = !repairedDocumentIsPersistent || !retainPersistent
            if (shouldDelete && file.exists() && !file.delete()) {
                NovaLog.w(TAG, "Unable to delete repaired PDF at ${file.absolutePath}")
            }
        }
        repairedDocumentFile = null
        repairedDocumentIsPersistent = false
    }

    private fun isPersistentRepairFile(file: File): Boolean {
        val parent = file.parentFile ?: return false
        if (parent != repairedDocumentDir) {
            return false
        }
        return file.name.startsWith(PERSISTENT_REPAIR_PREFIX)
    }

    private fun resolveRepairCacheKey(cacheKey: Uri?): String? {
        cacheKey ?: return null
        val scheme = cacheKey.scheme?.lowercase(Locale.US)
        return when (scheme) {
            ContentResolver.SCHEME_FILE -> {
                val path = cacheKey.path ?: return null
                if (isInCacheDirectory(path, repairedDocumentDir)) {
                    null
                } else {
                    File(path).absolutePath
                }
            }

            else -> cacheKey.toString()
        }
    }

    private fun buildPersistentRepairFile(cacheKey: String): File {
        val digest = sha256Hex(cacheKey)
        return File(repairedDocumentDir, "$PERSISTENT_REPAIR_PREFIX${digest}.pdf")
    }

    private fun persistRepairedFile(tempFile: File, targetFile: File): File {
        if (targetFile.exists() && !targetFile.delete()) {
            NovaLog.w(TAG, "Unable to delete stale cached repair at ${targetFile.absolutePath}")
        }
        if (tempFile.renameTo(targetFile)) {
            return targetFile
        }
        return try {
            tempFile.copyTo(targetFile, overwrite = true)
            if (tempFile.exists() && !tempFile.delete()) {
                NovaLog.w(TAG, "Unable to delete temporary repaired PDF at ${tempFile.absolutePath}")
            }
            targetFile
        } catch (error: Exception) {
            if (targetFile.exists() && !targetFile.delete()) {
                NovaLog.w(TAG, "Unable to delete failed cached repair at ${targetFile.absolutePath}")
            }
            throw error
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[unsigned ushr 4])
            builder.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return builder.toString()
    }

    private suspend fun attemptPdfRepair(
        uri: Uri,
        cacheKey: Uri? = null,
        cancellationSignal: CancellationSignal? = null,
    ): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            if (isInCacheDirectory(uri.path, repairedDocumentDir)) {
                return null
            }
        }

        cancellationSignal.throwIfCanceled()
        val pdfBoxReady = try {
            PdfBoxInitializer.ensureInitialized(appContext)
        } catch (error: Throwable) {
            NovaLog.w(TAG, "Unable to initialise PDFBox resources for repair", error)
            false
        }
        if (!pdfBoxReady) {
            return null
        }

        val repairDir = repairedDocumentDir
        if (!repairDir.isDirectory) {
            NovaLog.w(TAG, "Cannot repair PDF; repair cache directory is unavailable at ${repairDir.absolutePath}")
            return null
        }

        val cacheKeyString = resolveRepairCacheKey(cacheKey ?: uri)
        val persistentTarget = cacheKeyString?.let { buildPersistentRepairFile(it) }
        if (persistentTarget != null && persistentTarget.exists()) {
            if (persistentTarget.length() > 0L) {
                crashReporter?.logBreadcrumb("pdfium repair cache hit: ${cacheKeyString}")
                return persistentTarget
            } else if (!persistentTarget.delete()) {
                NovaLog.w(TAG, "Unable to delete empty cached repair at ${persistentTarget.absolutePath}")
            }
        }

        val input = try {
            contentResolver.openInputStream(uri)
        } catch (openError: Exception) {
            NovaLog.w(TAG, "Unable to open input stream for PDF repair", openError)
            null
        }
        if (input == null) {
            return null
        }

        val repairedFile = try {
            File.createTempFile("repaired-", ".pdf", repairDir)
        } catch (createError: IOException) {
            NovaLog.w(TAG, "Unable to create temporary file for PDF repair", createError)
            try {
                input.close()
            } catch (_: IOException) {
            }
            return null
        }

        return try {
            withTimeout(CONTENT_RESOLVER_READ_TIMEOUT_MS) {
                input.buffered().use { stream ->
                    cancellationSignal.throwIfCanceled()
                    PDDocument.load(stream).use { document ->
                        document.save(repairedFile)
                    }
                    val result = if (persistentTarget != null) {
                        persistRepairedFile(repairedFile, persistentTarget)
                    } else {
                        repairedFile
                    }
                    val breadcrumbTarget = cacheKeyString ?: uri.toString()
                    crashReporter?.logBreadcrumb("pdfium repair: ${breadcrumbTarget}")
                    result
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            NovaLog.w(TAG, "Timed out while attempting PDF repair", timeout)
            if (repairedFile.exists() && !repairedFile.delete()) {
                NovaLog.w(TAG, "Unable to delete timed out repaired PDF at ${repairedFile.absolutePath}")
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (repairError: Exception) {
            NovaLog.w(TAG, "Unable to repair PDF via PdfBox", repairError)
            if (repairedFile.exists() && !repairedFile.delete()) {
                NovaLog.w(TAG, "Unable to delete failed repaired PDF at ${repairedFile.absolutePath}")
            }
            persistentTarget?.let { target ->
                if (target.exists() && !target.delete()) {
                    NovaLog.w(TAG, "Unable to delete failed cached repair at ${target.absolutePath}")
                }
            }
            null
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun emitRenderProgressForTesting(progress: PdfRenderProgress) {
        updateRenderProgress(progress)
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun reportBitmapPoolMetricsForTesting() {
        bitmapPool.forceReport()
    }

    private fun calculateCacheBudget(): Long {
        val runtimeLimit = Runtime.getRuntime().maxMemory() / 4
        val clamped = min(CACHE_BUDGET_BYTES, runtimeLimit)
        return max(1L, clamped)
    }

    private fun reportBitmapPoolMetrics(snapshot: BitmapPoolSnapshot) {
        val hitPercentage = snapshot.hitRate * 100.0
        val message = String.format(
            Locale.US,
            "bitmap_pool requests=%d hits=%d misses=%d hitRate=%.2f%% releases=%d rejects=%d evictions=%d poolSize=%d poolBytes=%d/%d",
            snapshot.requests,
            snapshot.hits,
            snapshot.misses,
            hitPercentage,
            snapshot.releases,
            snapshot.rejects,
            snapshot.evictions,
            snapshot.poolSize,
            snapshot.poolBytes,
            snapshot.maxPoolBytes,
        )
        NovaLog.d(TAG, message)
        crashReporter?.logBreadcrumb(message)
    }

    private fun logBitmapMemoryThreshold(level: BitmapMemoryLevel, stats: BitmapMemoryStats) {
        val levelLabel = level.name.lowercase(Locale.US)
        val message = String.format(
            Locale.US,
            "bitmap_memory level=%s current=%s peak=%s warn=%s critical=%s",
            levelLabel,
            formatMemoryForLog(stats.currentBytes),
            formatMemoryForLog(stats.peakBytes),
            formatMemoryForLog(stats.warnThresholdBytes),
            formatMemoryForLog(stats.criticalThresholdBytes),
        )
        when (level) {
            BitmapMemoryLevel.WARNING -> NovaLog.w(TAG, message)
            BitmapMemoryLevel.CRITICAL -> NovaLog.e(TAG, message)
            BitmapMemoryLevel.NORMAL -> NovaLog.i(TAG, message)
        }
        crashReporter?.logBreadcrumb(message)
    }

    private fun formatMemoryForLog(bytes: Long): String {
        val megabytes = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.2fMB (%dB)", megabytes, bytes)
    }

    private fun safeByteCount(bitmap: Bitmap): Int = try {
        bitmap.byteCount
    } catch (ignored: IllegalStateException) {
        0
    }

    private fun safeAllocationByteCount(bitmap: Bitmap): Int = try {
        bitmap.allocationByteCount
    } catch (_: IllegalStateException) {
        safeByteCount(bitmap)
    }

    private fun bitmapConfigFor(profile: PageRenderProfile): Bitmap.Config = when (profile) {
        PageRenderProfile.HIGH_DETAIL -> Bitmap.Config.ARGB_8888
        PageRenderProfile.LOW_DETAIL -> Bitmap.Config.RGB_565
    }

    private fun profileForConfig(config: Bitmap.Config?): PageRenderProfile = when (config) {
        Bitmap.Config.RGB_565 -> PageRenderProfile.LOW_DETAIL
        else -> PageRenderProfile.HIGH_DETAIL
    }

    @Suppress("DEPRECATION")
    private fun bytesPerPixel(config: Bitmap.Config): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            config == Bitmap.Config.RGBA_1010102
        ) {
            return 4
        }
        return when (config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565 -> 2
            Bitmap.Config.ARGB_4444 -> 2
            Bitmap.Config.ARGB_8888 -> 4
            Bitmap.Config.RGBA_F16 -> 8
            Bitmap.Config.HARDWARE -> throw IllegalArgumentException("Bitmap.Config.HARDWARE is not supported by the pool")
            else -> 4
        }
    }

    private inline fun <T> withPdfiumPage(
        session: PdfDocumentSession,
        pageIndex: Int,
        block: () -> T,
    ): T {
        var opened = false
        return withPdfiumDocument(session.document, session.documentId) {
            try {
                pdfiumCore.openPage(session.document, pageIndex)
                opened = true
                block()
            } finally {
                if (opened) {
                    closePdfiumPage(session.document, pageIndex)
                }
            }
        }
    }

    private inline fun <T> withPdfiumDocument(
        document: PdfDocument,
        documentToken: Any?,
        block: () -> T,
    ): T {
        ensureWorkerThread()
        pdfiumCallVerifier.onEnter(document, documentToken)
        return try {
            block()
        } finally {
            pdfiumCallVerifier.onExit(document)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun closePdfiumPage(document: PdfDocument, pageIndex: Int) {
        val pagesField = pdfiumPagesField ?: return
        val closePage = pdfiumClosePageMethod ?: return
        val lock = pdfiumLockObject ?: return
        val pages = runCatching { pagesField.get(document) as? MutableMap<Int, Long> }.getOrNull() ?: return
        synchronized(lock) {
            val pointer = pages.remove(pageIndex) ?: return
            try {
                closePage.invoke(pdfiumCore, pointer)
            } catch (error: Throwable) {
                pages[pageIndex] = pointer
                NovaLog.w(TAG, "Failed to close Pdfium page $pageIndex", error)
            }
        }
    }

    private fun obtainBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        val pooled = bitmapPool.acquire(width, height, config)
        if (pooled != null) {
            return pooled
        }
        return Bitmap.createBitmap(width, height, config).also(::recordBitmapAllocation)
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled && !bitmapPool.release(bitmap)) {
            destroyBitmap(bitmap)
        }
    }

    private fun destroyBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            return
        }
        recordBitmapRelease(bitmap)
        bitmap.recycle()
    }

    private fun recordBitmapAllocation(bitmap: Bitmap) {
        val bytes = safeAllocationByteCount(bitmap).takeIf { it > 0 }?.toLong() ?: return
        bitmapMemoryTracker.onAllocated(bytes)
    }

    private fun recordBitmapRelease(bitmap: Bitmap) {
        val bytes = safeAllocationByteCount(bitmap).takeIf { it > 0 }?.toLong() ?: return
        bitmapMemoryTracker.onReleased(bytes)
    }

    private fun createBitmapCache(): BitmapCache {
        val caffeinePreconditionError = caffeinePreconditionFailure()
        if (caffeinePreconditionError != null) {
            NovaLog.w(TAG, "Caffeine bitmap cache disabled; falling back to LruCache", caffeinePreconditionError)
            reportNonFatal(
                caffeinePreconditionError,
                mapOf(
                    "stage" to "bitmapCacheInit",
                    "fallback" to "lru",
                    "reason" to "caffeine_precondition_failed",
                    "error_type" to caffeinePreconditionError::class.java.name
                )
            )
            return LruBitmapCache(maxCacheBytes)
        }

        return try {
            CaffeineBitmapCache(maxCacheBytes)
        } catch (error: Throwable) {
            NovaLog.w(TAG, "Falling back to LruCache for bitmap caching", error)
            reportNonFatal(
                error,
                mapOf(
                    "stage" to "bitmapCacheInit",
                    "fallback" to "lru"
                )
            )
            LruBitmapCache(maxCacheBytes)
        }
    }

    private fun caffeinePreconditionFailure(): Throwable? {
        return runCatching {
            Thread::class.java.getDeclaredField("threadLocalRandomProbe")
        }.exceptionOrNull()
    }

    private fun clearBitmapCacheLocked() {
        val bitmaps = bitmapCache.values().toList()
        bitmapCache.clearAll()
        bitmapCache.cleanUp()
        bitmaps.forEach(::recycleBitmap)
        bitmapPool.clear()
    }

    private fun scheduleCacheClear() {
        renderScope.launch {
            cacheLock.withLock { clearBitmapCacheLocked() }
        }
    }

    private fun scheduleCacheTrim(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped >= 1f) {
            return
        }
        renderScope.launch {
            cacheLock.withLock {
                if (clamped <= 0f) {
                    clearBitmapCacheLocked()
                } else {
                    bitmapCache.trimToFraction(clamped)
                    bitmapPool.clear()
                }
            }
        }
    }

    private fun reportNonFatal(throwable: Throwable, metadata: Map<String, String> = emptyMap()) {
        crashReporter?.recordNonFatal(throwable, metadata)
    }

    @WorkerThread
    private suspend fun parseDocumentOutline(session: PdfDocumentSession): List<PdfOutlineNode> {
        return withContext(ioDispatcher) {
            ensureWorkerThread()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return@withContext emptyList()
            }
            try {
                val duplicate = ParcelFileDescriptor.dup(session.fileDescriptor.fileDescriptor)
                duplicate.use { descriptor ->
                    parseOutlineFromDescriptor(descriptor)
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    @WorkerThread
    private fun parseOutlineFromDescriptor(descriptor: ParcelFileDescriptor): List<PdfOutlineNode> {
        ensureWorkerThread()
        return try {
            val pdfDocumentClass = Class.forName("android.graphics.pdf.PdfDocument")
            val instance = instantiatePdfDocument(pdfDocumentClass, descriptor) ?: return emptyList()
            try {
                val outlineMethod = pdfDocumentClass.methods.firstOrNull { method ->
                    method.parameterCount == 0 &&
                        (method.name.equals("getTableOfContents", ignoreCase = true) ||
                            method.name.equals("getDocumentOutline", ignoreCase = true) ||
                            method.name.equals("getOutline", ignoreCase = true) ||
                            method.name.equals("getBookmarks", ignoreCase = true))
                } ?: return emptyList()
                val outline = outlineMethod.invoke(instance) as? List<*> ?: return emptyList()
                outline.mapNotNull { it?.let(::toOutlineNode) }
            } finally {
                pdfDocumentClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                    ?.invoke(instance)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @WorkerThread
    private fun instantiatePdfDocument(pdfDocumentClass: Class<*>, descriptor: ParcelFileDescriptor): Any? {
        ensureWorkerThread()
        pdfDocumentClass.constructors.firstOrNull { constructor ->
            constructor.parameterCount == 1 && ParcelFileDescriptor::class.java.isAssignableFrom(constructor.parameterTypes[0])
        }?.let { constructor ->
            return try {
                constructor.newInstance(descriptor)
            } catch (_: Throwable) {
                null
            }
        }
        pdfDocumentClass.methods.firstOrNull { method ->
            method.parameterCount == 1 && method.name.equals("open", ignoreCase = true) &&
                ParcelFileDescriptor::class.java.isAssignableFrom(method.parameterTypes[0])
        }?.let { method ->
            return try {
                method.invoke(null, descriptor)
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun toOutlineNode(bookmark: Any): PdfOutlineNode? {
        return try {
            val bookmarkClass = bookmark.javaClass
            val titleMethod = bookmarkClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && method.name.contains("title", ignoreCase = true)
            }
            val titleValue = titleMethod?.invoke(bookmark) as? CharSequence ?: return null
            val pageMethod = bookmarkClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && (
                    method.name.contains("Page", ignoreCase = true) ||
                        method.name.contains("Destination", ignoreCase = true)
                    )
            }
            val pageIndex = when (val value = pageMethod?.invoke(bookmark)) {
                is Number -> value.toInt()
                else -> 0
            }.coerceAtLeast(0)
            val childrenMethod = bookmarkClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && method.name.contains("Child", ignoreCase = true) &&
                    List::class.java.isAssignableFrom(method.returnType)
            }
            val children = (childrenMethod?.invoke(bookmark) as? List<*>)
                ?.mapNotNull { it?.let(::toOutlineNode) }
                .orEmpty()
            PdfOutlineNode(
                title = titleValue.toString(),
                pageIndex = pageIndex,
                children = children
            )
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val LOG_MODULE = "PdfDocumentRepository"
        private const val DEFAULT_PRINT_BUFFER_SIZE = 16 * 1024
        private val KIDS_ARRAY_PATTERN: Pattern = Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL)
        private val KID_REFERENCE_PATTERN: Pattern = Pattern.compile("\\d+\\s+\\d+\\s+R")
    }

    private abstract inner class BitmapCache {
        private val aliasMutex = Mutex()
        private val aliasKeys = mutableMapOf<String, PageBitmapKey>()
        private var nextAliasId = 0

        fun get(key: PageBitmapKey): Bitmap? = getInternal(key)?.takeIf { !it.isRecycled }

        fun put(key: PageBitmapKey, bitmap: Bitmap) {
            putInternal(key, bitmap)
        }

        fun values(): Collection<Bitmap> = valuesInternal().filterNot(Bitmap::isRecycled)

        fun clearAll() {
            aliasMutex.withLockBlocking(this) {
                aliasKeys.clear()
                nextAliasId = 0
            }
            clearInternal()
        }

        fun cleanUp() {
            cleanUpInternal()
        }

        fun trimToFraction(fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            when {
                clamped <= 0f -> clearAll()
                clamped >= 1f -> Unit
                else -> trimInternal(clamped)
            }
        }

        @Suppress("unused")
        fun putBitmap(name: String, bitmap: Bitmap) {
            val aliasKey = aliasMutex.withLockBlocking(this) {
                aliasKeys.getOrPut(name) {
                    nextAliasId -= 1
                    val width = bitmap.width.takeIf { it > 0 } ?: 1
                    val profile = profileForConfig(bitmap.config)
                    PageBitmapKey(nextAliasId, width, profile)
                }
            }
            putInternal(aliasKey, bitmap)
        }

        @Suppress("unused")
        fun getBitmap(name: String): Bitmap? {
            val aliasKey = aliasMutex.withLockBlocking(this) { aliasKeys[name] } ?: return null
            return getInternal(aliasKey)?.takeIf { !it.isRecycled }
        }

        protected abstract fun getInternal(key: PageBitmapKey): Bitmap?
        protected abstract fun putInternal(key: PageBitmapKey, bitmap: Bitmap)
        protected abstract fun valuesInternal(): Collection<Bitmap>
        protected abstract fun clearInternal()
        protected abstract fun cleanUpInternal()
        protected abstract fun trimInternal(fraction: Float)
    }

    private inner class BitmapMemoryTracker(
        private val warnThresholdBytes: Long,
        private val criticalThresholdBytes: Long,
    ) {
        private val lock = Any()
        private var currentBytes = 0L
        private var peakBytes = 0L
        private var currentLevel = BitmapMemoryLevel.NORMAL

        fun onAllocated(bytes: Long) {
            if (bytes <= 0L) return
            update(bytes)
        }

        fun onReleased(bytes: Long) {
            if (bytes <= 0L) return
            update(-bytes)
        }

        private fun update(delta: Long) {
            if (delta == 0L) return
            val (stats, previousLevel) = synchronized(lock) {
                currentBytes = (currentBytes + delta).coerceAtLeast(0L)
                if (currentBytes > peakBytes) {
                    peakBytes = currentBytes
                }
                val nextLevel = determineLevel(currentBytes)
                val previous = currentLevel
                currentLevel = nextLevel
                val stats = BitmapMemoryStats(
                    currentBytes = currentBytes,
                    peakBytes = peakBytes,
                    warnThresholdBytes = warnThresholdBytes,
                    criticalThresholdBytes = criticalThresholdBytes,
                    level = nextLevel,
                )
                stats to previous
            }
            _bitmapMemoryStats.value = stats
            if (
                stats.level != BitmapMemoryLevel.NORMAL &&
                severity(stats.level) > severity(previousLevel)
            ) {
                logBitmapMemoryThreshold(stats.level, stats)
            }
        }

        private fun determineLevel(currentBytes: Long): BitmapMemoryLevel {
            if (criticalThresholdBytes > 0L && currentBytes >= criticalThresholdBytes) {
                return BitmapMemoryLevel.CRITICAL
            }
            if (warnThresholdBytes > 0L && currentBytes >= warnThresholdBytes) {
                return BitmapMemoryLevel.WARNING
            }
            return BitmapMemoryLevel.NORMAL
        }

        private fun severity(level: BitmapMemoryLevel): Int = when (level) {
            BitmapMemoryLevel.NORMAL -> 0
            BitmapMemoryLevel.WARNING -> 1
            BitmapMemoryLevel.CRITICAL -> 2
        }
    }

    private inner class BitmapPool(
        maxBytes: Long,
        private val reportInterval: Int,
        private val onSnapshot: (BitmapPoolSnapshot) -> Unit,
    ) {
        private val maxPoolBytes = maxBytes.coerceAtLeast(0L)
        private val mutex = Mutex()
        private val buckets = java.util.TreeMap<Int, ArrayDeque<Bitmap>>()
        private val identities = IdentityHashMap<Bitmap, Bitmap>()
        private val metrics = BitmapPoolMetricsTracker(maxPoolBytes, reportInterval, onSnapshot)
        private var totalBytes: Long = 0
        private var bitmapCount: Int = 0

        fun acquire(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
            if (maxPoolBytes <= 0L) {
                metrics.onAcquire(hit = false, totalBytes = 0L, poolSize = 0)
                return null
            }
            var bitmap: Bitmap? = null
            var hit = false
            var totalBytesSnapshot = 0L
            var poolSizeSnapshot = 0
            mutex.withLockBlocking(this) {
                bitmap = removeCandidate(width, height, config)
                hit = bitmap != null
                totalBytesSnapshot = totalBytes
                poolSizeSnapshot = bitmapCount
            }
            metrics.onAcquire(hit, totalBytesSnapshot, poolSizeSnapshot)
            return bitmap
        }

        fun release(bitmap: Bitmap): Boolean {
            if (maxPoolBytes <= 0L) {
                metrics.onRelease(false, totalBytes = 0L, poolSize = 0)
                return false
            }
            var accepted = false
            var totalBytesSnapshot = 0L
            var poolSizeSnapshot = 0
            mutex.withLockBlocking(this) {
                totalBytesSnapshot = totalBytes
                poolSizeSnapshot = bitmapCount
                if (bitmap.isRecycled) {
                    return@withLockBlocking
                }
                val config = bitmap.config ?: return@withLockBlocking
                if (!bitmap.isMutable || config == Bitmap.Config.HARDWARE) {
                    return@withLockBlocking
                }
                val capacity = safeAllocationByteCount(bitmap)
                if (capacity <= 0) {
                    return@withLockBlocking
                }
                if (identities.containsKey(bitmap)) {
                    accepted = true
                    return@withLockBlocking
                }
                if (totalBytes + capacity > maxPoolBytes) {
                    return@withLockBlocking
                }
                val bucket = buckets.getOrPut(capacity) { ArrayDeque() }
                bucket.addLast(bitmap)
                identities[bitmap] = bitmap
                totalBytes += capacity
                bitmapCount += 1
                accepted = true
                totalBytesSnapshot = totalBytes
                poolSizeSnapshot = bitmapCount
            }
            metrics.onRelease(accepted, totalBytesSnapshot, poolSizeSnapshot)
            return accepted
        }

        fun clear() {
            val drained = mutableListOf<Bitmap>()
            var evicted = 0
            mutex.withLockBlocking(this) {
                if (buckets.isEmpty()) {
                    return@withLockBlocking
                }
                buckets.values.forEach { bucket ->
                    drained.addAll(bucket)
                }
                evicted = drained.size
                buckets.clear()
                identities.clear()
                totalBytes = 0
                bitmapCount = 0
            }
            if (evicted > 0) {
                metrics.onEvict(evicted, totalBytes = 0L, poolSize = 0)
            }
            drained.forEach(::destroyBitmap)
        }

        fun forceReport() {
            val (totalBytesSnapshot, poolSizeSnapshot) = mutex.withLockBlocking(this) {
                totalBytes to bitmapCount
            }
            metrics.forceReport(totalBytesSnapshot, poolSizeSnapshot)
        }

        private fun removeCandidate(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
            val requiredBytes = requiredBytes(width, height, config)
            var entry = buckets.ceilingEntry(requiredBytes)
            while (entry != null) {
                val bucket = entry.value
                while (bucket.isNotEmpty()) {
                    val candidate = bucket.removeLast()
                    bitmapCount = (bitmapCount - 1).coerceAtLeast(0)
                    val reclaimedBytes = safeAllocationByteCount(candidate)
                        .takeIf { it > 0 }
                        ?.toLong()
                        ?: 0L
                    totalBytes = (totalBytes - reclaimedBytes).coerceAtLeast(0L)
                    identities.remove(candidate)
                    if (candidate.isRecycled) {
                        continue
                    }
                    val candidateConfig = candidate.config ?: continue
                    if (!candidate.isMutable || candidateConfig == Bitmap.Config.HARDWARE) {
                        destroyBitmap(candidate)
                        continue
                    }
                    if (candidateConfig != config) {
                        destroyBitmap(candidate)
                        continue
                    }
                    try {
                        if (candidate.width != width || candidate.height != height) {
                            candidate.reconfigure(width, height, config)
                        }
                        candidate.eraseColor(Color.TRANSPARENT)
                        return candidate
                    } catch (error: Throwable) {
                        destroyBitmap(candidate)
                    }
                }
                if (bucket.isEmpty()) {
                    buckets.remove(entry.key)
                }
                entry = buckets.ceilingEntry(requiredBytes)
            }
            return null
        }

        private fun requiredBytes(width: Int, height: Int, config: Bitmap.Config): Int {
            val pixels = width.toLong() * height.toLong()
            val bytesPerPixel = bytesPerPixel(config)
            val total = (pixels * bytesPerPixel).coerceAtMost(Int.MAX_VALUE.toLong())
            return total.toInt()
        }
    }

    private inner class BitmapPoolMetricsTracker(
        private val maxBytes: Long,
        private val reportInterval: Int,
        private val reporter: (BitmapPoolSnapshot) -> Unit,
    ) {
        private var acquireCount = 0L
        private var hitCount = 0L
        private var missCount = 0L
        private var releaseCount = 0L
        private var rejectCount = 0L
        private var evictionCount = 0L
        private var lastReportedOperation = 0L

        fun onAcquire(hit: Boolean, totalBytes: Long, poolSize: Int) {
            acquireCount += 1
            if (hit) {
                hitCount += 1
            } else {
                missCount += 1
            }
            maybeReport(totalBytes, poolSize, force = false)
        }

        fun onRelease(accepted: Boolean, totalBytes: Long, poolSize: Int) {
            releaseCount += 1
            if (!accepted) {
                rejectCount += 1
                maybeReport(totalBytes, poolSize, force = true)
            } else {
                maybeReport(totalBytes, poolSize, force = false)
            }
        }

        fun onEvict(evicted: Int, totalBytes: Long, poolSize: Int) {
            if (evicted > 0) {
                evictionCount += evicted
                maybeReport(totalBytes, poolSize, force = true)
            }
        }

        fun forceReport(totalBytes: Long, poolSize: Int) {
            if (acquireCount == 0L && releaseCount == 0L && evictionCount == 0L) {
                return
            }
            publishSnapshot(totalBytes, poolSize)
        }

        private fun maybeReport(totalBytes: Long, poolSize: Int, force: Boolean) {
            val operations = acquireCount + releaseCount
            if (!force && operations - lastReportedOperation < reportInterval) {
                return
            }
            publishSnapshot(totalBytes, poolSize)
        }

        private fun publishSnapshot(totalBytes: Long, poolSize: Int) {
            lastReportedOperation = acquireCount + releaseCount
            reporter(
                BitmapPoolSnapshot(
                    requests = acquireCount,
                    hits = hitCount,
                    misses = missCount,
                    releases = releaseCount,
                    rejects = rejectCount,
                    evictions = evictionCount,
                    poolSize = poolSize,
                    poolBytes = totalBytes,
                    maxPoolBytes = maxBytes,
                )
            )
        }
    }

    data class BitmapPoolSnapshot(
        val requests: Long,
        val hits: Long,
        val misses: Long,
        val releases: Long,
        val rejects: Long,
        val evictions: Long,
        val poolSize: Int,
        val poolBytes: Long,
        val maxPoolBytes: Long,
    ) {
        val hitRate: Double
            get() = if (requests <= 0L) 0.0 else hits.toDouble() / requests.toDouble()
    }

    private inner class CaffeineBitmapCache(maxCacheBytes: Long) : BitmapCache() {
        private val maximumWeight = maxCacheBytes.coerceAtLeast(0L)
        private val delegate = Caffeine.newBuilder()
            .maximumWeight(maxCacheBytes)
            .weigher<PageBitmapKey, Bitmap> { _, bitmap -> safeByteCount(bitmap) }
            .executor { it.run() }
            .removalListener<PageBitmapKey, Bitmap> { _, bitmap, cause ->
                if (cause == RemovalCause.REPLACED || cause == RemovalCause.EXPLICIT || cause.wasEvicted()) {
                    bitmap?.let { recycleBitmap(it) }
                }
            }
            .build<PageBitmapKey, Bitmap>()

        override fun getInternal(key: PageBitmapKey): Bitmap? = delegate.getIfPresent(key)

        override fun putInternal(key: PageBitmapKey, bitmap: Bitmap) {
            delegate.put(key, bitmap)
        }

        override fun valuesInternal(): Collection<Bitmap> = delegate.asMap().values

        override fun clearInternal() {
            delegate.invalidateAll()
        }

        override fun cleanUpInternal() {
            delegate.cleanUp()
        }

        override fun trimInternal(fraction: Float) {
            val eviction = delegate.policy().eviction().orElse(null) ?: return
            val currentSize = eviction.weightedSize().orElse(0L)
            if (currentSize <= 0L) {
                return
            }
            val target = (currentSize.toDouble() * fraction.toDouble()).toLong().coerceAtLeast(0L)
            eviction.setMaximum(target)
            delegate.cleanUp()
            eviction.setMaximum(maximumWeight)
        }
    }

    private inner class LruBitmapCache(maxCacheBytes: Long) : BitmapCache() {
        private val cacheSize = maxCacheBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        private val mutex = Mutex()
        private val delegate = object : LruCache<PageBitmapKey, Bitmap>(cacheSize) {
            override fun sizeOf(key: PageBitmapKey, value: Bitmap): Int = safeByteCount(value)

            override fun entryRemoved(evicted: Boolean, key: PageBitmapKey, oldValue: Bitmap, newValue: Bitmap?) {
                if (evicted || newValue == null) {
                    recycleBitmap(oldValue)
                }
            }
        }

        override fun getInternal(key: PageBitmapKey): Bitmap? = mutex.withLockBlocking(this) {
            delegate.get(key)
        }

        override fun putInternal(key: PageBitmapKey, bitmap: Bitmap) {
            val previous = mutex.withLockBlocking(this) {
                delegate.put(key, bitmap)
            }
            if (previous != null && previous !== bitmap) {
                recycleBitmap(previous)
            }
        }

        override fun valuesInternal(): Collection<Bitmap> = mutex.withLockBlocking(this) {
            delegate.snapshot().values.toList()
        }

        override fun clearInternal() {
            mutex.withLockBlocking(this) {
                delegate.evictAll()
            }
        }

        override fun cleanUpInternal() {
            // LruCache does not require explicit cleanup.
        }

        override fun trimInternal(fraction: Float) {
            mutex.withLockBlocking(this) {
                val currentSize = delegate.size()
                if (currentSize <= 0) {
                    return@withLockBlocking
                }
                val target = (currentSize.toDouble() * fraction.toDouble()).toInt().coerceAtLeast(0)
                delegate.trimToSize(target)
            }
        }
    }

    private inline fun <T> Mutex.withLockBlocking(owner: Any, block: () -> T): T {
        var acquired = tryLock(owner)
        if (!acquired) {
            runBlocking { lock(owner) }
            acquired = true
        }
        return try {
            block()
        } finally {
            if (acquired) {
                unlock(owner)
            }
        }
    }

    private fun CancellationSignal?.throwIfCanceled() {
        if (this?.isCanceled == true) {
            throw CancellationException("PDF operation cancelled")
        }
    }

    private class PdfiumCallVerifier {
        private val lock = Any()
        private val activeDocuments = IdentityHashMap<PdfDocument, String>()

        fun onEnter(document: PdfDocument, token: Any?) {
            val description = describe(document, token)
            synchronized(lock) {
                if (activeDocuments.containsKey(document)) {
                    val active = activeDocuments[document]
                    throw IllegalStateException(
                        "Pdfium JNI call re-entered concurrently for document ${active ?: description}"
                    )
                }
                activeDocuments[document] = description
            }
        }

        fun onExit(document: PdfDocument) {
            synchronized(lock) {
                val removed = activeDocuments.remove(document)
                if (removed == null) {
                    NovaLog.w(TAG, "Pdfium document ${describe(document, null)} was not tracked by verifier on exit")
                }
            }
        }

        private fun describe(document: PdfDocument, token: Any?): String {
            val provided = token?.toString()
            return provided ?: "PdfDocument@${Integer.toHexString(System.identityHashCode(document))}"
        }
    }
}

@VisibleForTesting
fun normalizedMimeType(
    reportedMimeType: String?,
    fileExtension: String?,
): String? {
    val reported = reportedMimeType?.lowercase(Locale.US)?.trim()
    if (!reported.isNullOrEmpty()) {
        if (reported.contains("pdf")) {
            return "application/pdf"
        }
        if (reported != "application/octet-stream") {
            return reported
        }
    }

    val extension = fileExtension?.lowercase(Locale.US)?.trim()
    if (!extension.isNullOrEmpty()) {
        if (extension == "pdf") {
            return "application/pdf"
        }
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.lowercase(Locale.US)
            ?.let { return it }
    }

    return if (reported == "application/octet-stream") {
        null
    } else {
        reported
    }
}

@VisibleForTesting
fun hasPdfMagic(buffer: ByteArray, bytesRead: Int): Boolean {
    if (bytesRead <= 0) {
        return false
    }

    val limit = min(bytesRead, buffer.size)
    var offset = 0

    if (limit >= 3 &&
        buffer[0] == 0xEF.toByte() &&
        buffer[1] == 0xBB.toByte() &&
        buffer[2] == 0xBF.toByte()
    ) {
        offset = 3
    }

    while (offset < limit) {
        val current = buffer[offset]
        if (!(current == 0x00.toByte() || current.toInt().toChar().isWhitespace())) {
            break
        }
        offset++
    }

    val magic = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte())
    if (limit - offset < magic.size) {
        return false
    }

    for (index in magic.indices) {
        if (buffer[offset + index] != magic[index]) {
            return false
        }
    }

    return true
}
