package com.novapdf.reader.data

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Trace
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.util.SparseArray
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.IOException
import java.util.IdentityHashMap
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.coroutines.coroutineContext
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.text.Charsets
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.search.PdfBoxInitializer
import com.tom_roush.pdfbox.pdmodel.PDDocument

private const val CACHE_BUDGET_BYTES = 50L * 1024L * 1024L
private const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L
private const val PRE_REPAIR_MIN_SIZE_BYTES = 2L * 1024L * 1024L
private const val PRE_REPAIR_SCAN_LIMIT_BYTES = 8L * 1024L * 1024L
private const val PRE_REPAIR_MAX_KIDS_PER_ARRAY = 32
private const val TAG = "PdfDocumentRepository"

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
    val width: Int
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
        File(appContext.cacheDir, "pdf-repairs").apply {
            if (!exists() && !mkdirs()) {
                Log.w(TAG, "Unable to create PDF repair cache at ${absolutePath}")
            } else if (exists() && !isDirectory) {
                Log.w(TAG, "PDF repair cache path is not a directory: ${absolutePath}")
            }
        }
    }
    private val cacheLock = Mutex()
    private val maxCacheBytes = calculateCacheBudget()
    private val pdfiumCore = PdfiumCore(appContext)
    private val bitmapCache = createBitmapCache()
    private val bitmapPool = BitmapPool(maxCacheBytes / 2)
    private val pageSizeCache = SparseArray<Size>()
    private val pageSizeLock = Mutex()
    private val openSession = MutableStateFlow<PdfDocumentSession?>(null)
    val session: StateFlow<PdfDocumentSession?> = openSession.asStateFlow()
    private val outlineNodes = MutableStateFlow<List<PdfOutlineNode>>(emptyList())
    val outline: StateFlow<List<PdfOutlineNode>> = outlineNodes.asStateFlow()
    private val renderProgressState = MutableStateFlow<PdfRenderProgress>(PdfRenderProgress.Idle)
    val renderProgress: StateFlow<PdfRenderProgress> = renderProgressState.asStateFlow()
    private val renderMutex = Mutex()
    private val componentCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() {
            scheduleCacheClear()
        }

        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                scheduleCacheClear()
            }
        }
    }

    private var repairedDocumentFile: File? = null

    init {
        appContext.registerComponentCallbacks(componentCallbacks)
    }

    @WorkerThread
    suspend fun open(uri: Uri, cancellationSignal: CancellationSignal? = null): PdfDocumentSession {
        return withContextGuard {
            cancellationSignal.throwIfCanceled()
            closeSessionInternal()
            repairedDocumentFile?.let { file ->
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Unable to delete previous repaired PDF at ${file.absolutePath}")
                }
            }
            repairedDocumentFile = null
            cancellationSignal.throwIfCanceled()
            if (!validateDocumentUri(uri)) {
                throw PdfOpenException(PdfOpenException.Reason.UNSUPPORTED)
            }
            val preparedFile = prepareLargeDocumentIfNeeded(uri, cancellationSignal)
            if (preparedFile != null) {
                repairedDocumentFile = preparedFile
            }
            val sourceUri = repairedDocumentFile?.toUri() ?: uri
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
                Log.e(TAG, "Failed to open PDF via Pdfium", throwable)
                reportNonFatal(
                    throwable,
                    mapOf(
                        "stage" to "pdfiumNewDocument",
                        "uri" to sourceUri.toString()
                    )
                )
                val repaired = attemptPdfRepair(uri)
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
                    Log.e(TAG, "Failed to open repaired PDF via Pdfium", second)
                    reportNonFatal(
                        second,
                        mapOf(
                            "stage" to "pdfiumNewDocumentRepair",
                            "uri" to uri.toString()
                        )
                    )
                    throw PdfOpenException(PdfOpenException.Reason.CORRUPTED, second)
                }

                if (repairedDocumentFile != null && repairedDocumentFile != repaired) {
                    cleanedUpRepairedFile(repairedDocumentFile!!)
                }
                repairedDocumentFile = repaired
                activePfd = repairedPfd
                repairedDocument
            }
            val pageCount = pdfiumCore.getPageCount(document)
            val session = PdfDocumentSession(
                documentId = uri.toString(),
                uri = repairedDocumentFile?.toUri() ?: uri,
                pageCount = pageCount,
                document = document,
                fileDescriptor = activePfd
            )
            openSession.value = session
            outlineNodes.value = parseDocumentOutline(session)
            session
        }
    }

    @WorkerThread
    suspend fun getPageSize(pageIndex: Int): Size? = withContextGuard {
        val session = openSession.value ?: return@withContextGuard null
        pageSizeLock.withLock {
            pageSizeCache[pageIndex]?.let { return@withLock it }
            if (!session.document.hasPage(pageIndex)) {
                pdfiumCore.openPage(session.document, pageIndex)
            }
            val width = pdfiumCore.getPageWidthPoint(session.document, pageIndex)
            val height = pdfiumCore.getPageHeightPoint(session.document, pageIndex)
            val size = Size(width, height)
            pageSizeCache.put(pageIndex, size)
            size
        }
    }

    @WorkerThread
    suspend fun renderPage(
        pageIndex: Int,
        targetWidth: Int,
        cancellationSignal: CancellationSignal? = null,
    ): Bitmap? = withContextGuard {
        cancellationSignal.throwIfCanceled()
        if (targetWidth <= 0) return@withContextGuard null
        val bitmap = ensurePageBitmap(
            pageIndex,
            targetWidth,
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
        if (openSession.value == null) return@withContextGuard null
        val pageSize = getPageSizeInternal(request.pageIndex) ?: return@withContextGuard null
        val targetWidth = max(1, (pageSize.width * request.scale).roundToInt())
        val baseBitmap = ensurePageBitmap(
            request.pageIndex,
            targetWidth,
            cancellationSignal = request.cancellationSignal,
        ) ?: return@withContextGuard null
        coroutineContext.ensureActive()
        val scaledLeft = (request.tileRect.left * request.scale).roundToInt().coerceIn(0, baseBitmap.width - 1)
        val scaledTop = (request.tileRect.top * request.scale).roundToInt().coerceIn(0, baseBitmap.height - 1)
        val scaledRight = (request.tileRect.right * request.scale).roundToInt().coerceIn(scaledLeft + 1, baseBitmap.width)
        val scaledBottom = (request.tileRect.bottom * request.scale).roundToInt().coerceIn(scaledTop + 1, baseBitmap.height)
        val width = (scaledRight - scaledLeft).coerceAtLeast(1)
        val height = (scaledBottom - scaledTop).coerceAtLeast(1)
        request.cancellationSignal.throwIfCanceled()
        coroutineContext.ensureActive()
        val tile = Bitmap.createBitmap(baseBitmap, scaledLeft, scaledTop, width, height)
        coroutineContext.ensureActive()
        if (tile.config != Bitmap.Config.ARGB_8888 && tile.config != Bitmap.Config.RGBA_F16) {
            copyBitmap(tile)
        } else {
            tile
        }
    }

    fun prefetchPages(indices: List<Int>, targetWidth: Int) {
        if (targetWidth <= 0) return
        val session = openSession.value ?: return
        if (indices.isEmpty()) return
        renderScope.launch {
            indices.forEach { index ->
                if (index in 0 until session.pageCount) {
                    // Avoid blocking foreground renders if decoding is already busy.
                    ensurePageBitmap(index, targetWidth, allowSkippingIfBusy = true)
                }
            }
        }
    }

    fun preloadTiles(indices: List<Int>, tileFractions: List<RectF>, scale: Float) {
        val session = openSession.value ?: return
        if (tileFractions.isEmpty()) return
        renderScope.launch {
            indices.forEach { pageIndex ->
                if (pageIndex in 0 until session.pageCount) {
                    val pageSize = getPageSizeInternal(pageIndex) ?: return@forEach
                    val targetWidth = max(1, (pageSize.width * scale).roundToInt())
                    // Skip speculative tile work when render mutex is contended.
                    ensurePageBitmap(pageIndex, targetWidth, allowSkippingIfBusy = true)
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
                        FileInputStream(source.fileDescriptor).use { inStream ->
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
            Log.w(TAG, "Rejected document with unsupported scheme: ${uri.scheme}")
            return false
        }

        val mimeType = resolveMimeType(uri)?.lowercase(Locale.US)
        if (mimeType != "application/pdf") {
            Log.w(TAG, "Rejected document with unsupported MIME type: $mimeType")
            return false
        }

        val size = resolveDocumentSize(uri)
        if (size == null || size <= 0L) {
            Log.w(TAG, "Rejected document with unknown or empty size")
            return false
        }
        if (size > MAX_DOCUMENT_BYTES) {
            Log.w(TAG, "Rejected document exceeding size limit: $size")
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
                Log.w(TAG, "Unknown MIME type for file extension .$extension from $uri")
            } else {
                Log.w(TAG, "Unable to resolve file extension for $uri")
            }
        } else if (
            normalized == "application/pdf" &&
            reported?.lowercase(Locale.US)?.contains("pdf") != true &&
            extension == "pdf"
        ) {
            Log.i(TAG, "Falling back to manual PDF MIME type detection for $uri")
        }

        return normalized
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
        return pageSizeLock.withLock {
            pageSizeCache[pageIndex]?.let { return@withLock it }
            try {
                if (!session.document.hasPage(pageIndex)) {
                    pdfiumCore.openPage(session.document, pageIndex)
                }
                val width = pdfiumCore.getPageWidthPoint(session.document, pageIndex)
                val height = pdfiumCore.getPageHeightPoint(session.document, pageIndex)
                val size = Size(width, height)
                pageSizeCache.put(pageIndex, size)
                size
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to obtain page size for index $pageIndex", throwable)
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

    @WorkerThread
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun ensurePageBitmap(
        pageIndex: Int,
        targetWidth: Int,
        allowSkippingIfBusy: Boolean = false,
        cancellationSignal: CancellationSignal? = null,
    ): Bitmap? {
        // When prefetching we avoid waiting on the render mutex so interactive draws remain responsive.
        ensureWorkerThread()
        cancellationSignal.throwIfCanceled()
        if (targetWidth <= 0) return null
        val session = openSession.value ?: return null
        val key = PageBitmapKey(pageIndex, targetWidth)
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

        return try {
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) {
                    return cached
                }
            }
            cancellationSignal.throwIfCanceled()
            val pageSize = getPageSizeInternal(pageIndex) ?: return null
            if (pageSize.width <= 0 || pageSize.height <= 0) {
                return null
            }
            val aspect = pageSize.height.toDouble() / pageSize.width.toDouble()
            val targetHeight = max(1, (aspect * targetWidth.toDouble()).roundToInt())
            updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0f))
            Trace.beginSection("PdfiumRender#$pageIndex")
            try {
                updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0.2f))
                cancellationSignal.throwIfCanceled()
                if (!session.document.hasPage(pageIndex)) {
                    pdfiumCore.openPage(session.document, pageIndex)
                }
                val bitmap = obtainBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0.6f))
                cancellationSignal.throwIfCanceled()
                pdfiumCore.renderPageBitmap(session.document, bitmap, pageIndex, 0, 0, targetWidth, targetHeight, true)
                updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 1f))
                bitmapCache.put(key, bitmap)
                bitmap
            } catch (throwable: Throwable) {
                Log.e(TAG, "Failed to render page $pageIndex", throwable)
                reportNonFatal(
                    throwable,
                    mapOf(
                        "stage" to "renderPage",
                        "pageIndex" to pageIndex.toString(),
                        "targetWidth" to targetWidth.toString()
                    )
                )
                null
            } finally {
                updateRenderProgress(PdfRenderProgress.Idle)
                Trace.endSection()
            }
        } finally {
            renderMutex.unlock(lockOwner)
        }
    }

    private fun copyBitmap(source: Bitmap): Bitmap? {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        return try {
            source.copy(config, false)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to copy bitmap", throwable)
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
                pdfiumCore.closeDocument(session.document)
                session.fileDescriptor.close()
            } catch (_: IOException) {
            }
        }
        openSession.value = null
        repairedDocumentFile?.let { file ->
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to delete repaired PDF at ${file.absolutePath}")
            }
        }
        repairedDocumentFile = null
    }

    @WorkerThread
    private fun updateRenderProgress(progress: PdfRenderProgress) {
        ensureWorkerThread()
        renderProgressState.value = progress
    }

    private suspend fun prepareLargeDocumentIfNeeded(
        uri: Uri,
        cancellationSignal: CancellationSignal? = null,
    ): File? {
        cancellationSignal.throwIfCanceled()
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (path != null && path.contains("/pdf-repairs/")) {
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

        Log.i(TAG, "Detected oversized page tree for $uri; attempting pre-emptive repair")
        cancellationSignal.throwIfCanceled()
        return attemptPdfRepair(uri, cancellationSignal)
    }

    private fun detectOversizedPageTree(
        uri: Uri,
        sizeHint: Long?,
        cancellationSignal: CancellationSignal? = null,
    ): Boolean {
        val buffer = readBytesForPageTreeInspection(uri, sizeHint, cancellationSignal) ?: return false
        val contents = try {
            String(buffer, Charsets.ISO_8859_1)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to decode PDF for page tree inspection", error)
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
                    Log.w(TAG, "Detected oversized /Kids array with $count entries for $uri")
                    return true
                }
            }
        }
        return false
    }

    private fun readBytesForPageTreeInspection(
        uri: Uri,
        sizeHint: Long?,
        cancellationSignal: CancellationSignal? = null,
    ): ByteArray? {
        val limit = when {
            sizeHint != null && sizeHint in 1L..PRE_REPAIR_SCAN_LIMIT_BYTES -> sizeHint.toInt()
            else -> PRE_REPAIR_SCAN_LIMIT_BYTES.toInt()
        }.coerceAtLeast(1)

        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
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
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to inspect PDF for page tree analysis", error)
            null
        } catch (error: IOException) {
            Log.w(TAG, "I/O error while inspecting PDF for page tree analysis", error)
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
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete repaired PDF at ${file.absolutePath}")
        }
    }

    private suspend fun attemptPdfRepair(
        uri: Uri,
        cancellationSignal: CancellationSignal? = null,
    ): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path
            if (path != null && path.contains("/pdf-repairs/")) {
                return null
            }
        }

        cancellationSignal.throwIfCanceled()
        val pdfBoxReady = try {
            PdfBoxInitializer.ensureInitialized(appContext)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to initialise PDFBox resources for repair", error)
            false
        }
        if (!pdfBoxReady) {
            return null
        }

        val repairDir = repairedDocumentDir
        if (!repairDir.isDirectory) {
            Log.w(TAG, "Cannot repair PDF; repair cache directory is unavailable at ${repairDir.absolutePath}")
            return null
        }

        val input = try {
            contentResolver.openInputStream(uri)
        } catch (openError: Exception) {
            Log.w(TAG, "Unable to open input stream for PDF repair", openError)
            null
        }
        if (input == null) {
            return null
        }

        val repairedFile = try {
            File.createTempFile("repaired-", ".pdf", repairDir)
        } catch (createError: IOException) {
            Log.w(TAG, "Unable to create temporary file for PDF repair", createError)
            try {
                input.close()
            } catch (_: IOException) {
            }
            return null
        }

        return input.use { stream ->
            try {
                cancellationSignal.throwIfCanceled()
                PDDocument.load(stream).use { document ->
                    document.save(repairedFile)
                }
                crashReporter?.logBreadcrumb("pdfium repair: ${uri}")
                repairedFile
            } catch (repairError: Exception) {
                Log.w(TAG, "Unable to repair PDF via PdfBox", repairError)
                if (repairedFile.exists() && !repairedFile.delete()) {
                    Log.w(TAG, "Unable to delete failed repaired PDF at ${repairedFile.absolutePath}")
                }
                null
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun emitRenderProgressForTesting(progress: PdfRenderProgress) {
        updateRenderProgress(progress)
    }

    private fun calculateCacheBudget(): Long {
        val runtimeLimit = Runtime.getRuntime().maxMemory() / 4
        val clamped = min(CACHE_BUDGET_BYTES, runtimeLimit)
        return max(1L, clamped)
    }

    private fun safeByteCount(bitmap: Bitmap): Int = try {
        bitmap.byteCount
    } catch (ignored: IllegalStateException) {
        0
    }

    private fun obtainBitmap(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        return bitmapPool.acquire(width, height, config) ?: Bitmap.createBitmap(width, height, config)
    }

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled && !bitmapPool.release(bitmap)) {
            bitmap.recycle()
        }
    }

    private fun createBitmapCache(): BitmapCache {
        val caffeinePreconditionError = caffeinePreconditionFailure()
        if (caffeinePreconditionError != null) {
            Log.w(TAG, "Caffeine bitmap cache disabled; falling back to LruCache", caffeinePreconditionError)
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
            Log.w(TAG, "Falling back to LruCache for bitmap caching", error)
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

        @Suppress("unused")
        fun putBitmap(name: String, bitmap: Bitmap) {
            val aliasKey = aliasMutex.withLockBlocking(this) {
                aliasKeys.getOrPut(name) {
                    nextAliasId -= 1
                    val width = bitmap.width.takeIf { it > 0 } ?: 1
                    PageBitmapKey(nextAliasId, width)
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
    }

    private inner class BitmapPool(maxBytes: Long) {
        private val maxPoolBytes = maxBytes.coerceAtLeast(0L)
        private val mutex = Mutex()
        private val pool = ArrayDeque<Bitmap>()
        private val identities = IdentityHashMap<Bitmap, Bitmap>()
        private var totalBytes: Long = 0

        fun acquire(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
            if (maxPoolBytes <= 0L) {
                return null
            }
            return mutex.withLockBlocking(this) {
                val iterator = pool.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    val reclaimedBytes = safeByteCount(candidate).takeIf { it > 0 }?.toLong() ?: 0L
                    if (candidate.isRecycled) {
                        iterator.remove()
                        identities.remove(candidate)
                        totalBytes = (totalBytes - reclaimedBytes).coerceAtLeast(0L)
                        continue
                    }
                    if (!candidate.isMutable || candidate.config != config) {
                        continue
                    }
                    if (candidate.width == width && candidate.height == height) {
                        iterator.remove()
                        identities.remove(candidate)
                        totalBytes = (totalBytes - reclaimedBytes).coerceAtLeast(0L)
                        return@withLockBlocking candidate
                    }
                }
                null
            }
        }

        fun release(bitmap: Bitmap): Boolean {
            if (maxPoolBytes <= 0L) {
                return false
            }
            if (bitmap.isRecycled || !bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
                return false
            }
            val bytes = safeByteCount(bitmap).takeIf { it > 0 }?.toLong() ?: return false
            return mutex.withLockBlocking(this) {
                if (identities.containsKey(bitmap)) {
                    return@withLockBlocking true
                }
                if (totalBytes + bytes > maxPoolBytes) {
                    return@withLockBlocking false
                }
                pool.addLast(bitmap)
                identities[bitmap] = bitmap
                totalBytes += bytes
                true
            }
        }

        fun clear() {
            val drained = mutableListOf<Bitmap>()
            mutex.withLockBlocking(this) {
                if (pool.isEmpty()) {
                    return@withLockBlocking
                }
                drained.addAll(pool)
                pool.clear()
                identities.clear()
                totalBytes = 0
            }
            drained.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
    }

    private inner class CaffeineBitmapCache(maxCacheBytes: Long) : BitmapCache() {
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
