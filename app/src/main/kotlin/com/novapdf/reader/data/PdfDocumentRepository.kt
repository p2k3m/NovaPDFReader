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
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.PdfRenderProgress

private const val CACHE_BUDGET_BYTES = 50L * 1024L * 1024L
private const val MAX_DOCUMENT_BYTES = 100L * 1024L * 1024L
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
    val scale: Float
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
    private val pdfDispatcher: CoroutineDispatcher = ioDispatcher.limitedParallelism(1)
    private val renderScope = CoroutineScope(Job() + pdfDispatcher)
    private val cacheLock = Mutex()
    private val maxCacheBytes = calculateCacheBudget()
    private val pdfiumCore = PdfiumCore(appContext)
    private val bitmapCache = createBitmapCache()
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

        override fun onLowMemory() {
            scheduleCacheClear()
        }

        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                scheduleCacheClear()
            }
        }
    }

    init {
        appContext.registerComponentCallbacks(componentCallbacks)
    }

    @WorkerThread
    suspend fun open(uri: Uri): PdfDocumentSession {
        return withContextGuard {
            closeSessionInternal()
            if (!validateDocumentUri(uri)) {
                throw PdfOpenException(PdfOpenException.Reason.UNSUPPORTED)
            }
            val pfd = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (security: SecurityException) {
                reportNonFatal(
                    security,
                    mapOf(
                        "stage" to "openFileDescriptor",
                        "uri" to uri.toString()
                    )
                )
                throw PdfOpenException(PdfOpenException.Reason.ACCESS_DENIED, security)
            } ?: throw PdfOpenException(PdfOpenException.Reason.ACCESS_DENIED)
            val document = try {
                pdfiumCore.newDocument(pfd)
            } catch (throwable: Throwable) {
                try {
                    pfd.close()
                } catch (_: IOException) {
                }
                Log.e(TAG, "Failed to open PDF via Pdfium", throwable)
                reportNonFatal(
                    throwable,
                    mapOf(
                        "stage" to "pdfiumNewDocument",
                        "uri" to uri.toString()
                    )
                )
                throw PdfOpenException(PdfOpenException.Reason.CORRUPTED, throwable)
            }
            val pageCount = pdfiumCore.getPageCount(document)
            val session = PdfDocumentSession(
                documentId = uri.toString(),
                uri = uri,
                pageCount = pageCount,
                document = document,
                fileDescriptor = pfd
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
    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? = withContextGuard {
        if (targetWidth <= 0) return@withContextGuard null
        val bitmap = ensurePageBitmap(pageIndex, targetWidth) ?: return@withContextGuard null
        copyBitmap(bitmap)
    }

    @WorkerThread
    suspend fun renderTile(pageIndex: Int, tileRect: Rect, scale: Float): Bitmap? {
        return renderTile(PageTileRequest(pageIndex, tileRect, scale))
    }

    @WorkerThread
    suspend fun renderTile(request: PageTileRequest): Bitmap? = withContextGuard {
        if (openSession.value == null) return@withContextGuard null
        val pageSize = getPageSizeInternal(request.pageIndex) ?: return@withContextGuard null
        val targetWidth = max(1, (pageSize.width * request.scale).roundToInt())
        val baseBitmap = ensurePageBitmap(request.pageIndex, targetWidth) ?: return@withContextGuard null
        val scaledLeft = (request.tileRect.left * request.scale).roundToInt().coerceIn(0, baseBitmap.width - 1)
        val scaledTop = (request.tileRect.top * request.scale).roundToInt().coerceIn(0, baseBitmap.height - 1)
        val scaledRight = (request.tileRect.right * request.scale).roundToInt().coerceIn(scaledLeft + 1, baseBitmap.width)
        val scaledBottom = (request.tileRect.bottom * request.scale).roundToInt().coerceIn(scaledTop + 1, baseBitmap.height)
        val width = (scaledRight - scaledLeft).coerceAtLeast(1)
        val height = (scaledBottom - scaledTop).coerceAtLeast(1)
        val tile = Bitmap.createBitmap(baseBitmap, scaledLeft, scaledTop, width, height)
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
        contentResolver.getType(uri)?.let { return it }
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val extensionFromUrl = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase(Locale.US)
            val extension = when {
                !extensionFromUrl.isNullOrEmpty() -> extensionFromUrl
                else -> uri.path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.US)
            }
            if (!extension.isNullOrEmpty()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)?.let { return it }
                if (extension == "pdf") {
                    Log.i(TAG, "Falling back to manual PDF MIME type detection for $uri")
                    return "application/pdf"
                }
                Log.w(TAG, "Unknown MIME type for file extension .$extension from $uri")
            } else {
                Log.w(TAG, "Unable to resolve file extension for $uri")
            }
        }
        return null
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
    private suspend fun ensurePageBitmap(
        pageIndex: Int,
        targetWidth: Int,
        allowSkippingIfBusy: Boolean = false,
    ): Bitmap? {
        // When prefetching we avoid waiting on the render mutex so interactive draws remain responsive.
        ensureWorkerThread()
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
        if (!lockAcquired) {
            return null
        }

        return try {
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) {
                    return cached
                }
            }
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
                if (!session.document.hasPage(pageIndex)) {
                    pdfiumCore.openPage(session.document, pageIndex)
                }
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                updateRenderProgress(PdfRenderProgress.Rendering(pageIndex, 0.6f))
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
                    "config" to (config?.name ?: "unknown")
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
    }

    @WorkerThread
    private fun updateRenderProgress(progress: PdfRenderProgress) {
        ensureWorkerThread()
        renderProgressState.value = progress
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun emitRenderProgressForTesting(progress: PdfRenderProgress) {
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

    private fun recycleBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun createBitmapCache(): BitmapCache {
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

    private fun clearBitmapCacheLocked() {
        bitmapCache.values().forEach(::recycleBitmap)
        bitmapCache.clearAll()
        bitmapCache.cleanUp()
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
    }

    private abstract inner class BitmapCache {
        private val aliasLock = Any()
        private val aliasKeys = mutableMapOf<String, PageBitmapKey>()
        private var nextAliasId = 0

        fun get(key: PageBitmapKey): Bitmap? = getInternal(key)?.takeIf { !it.isRecycled }

        fun put(key: PageBitmapKey, bitmap: Bitmap) {
            putInternal(key, bitmap)
        }

        fun values(): Collection<Bitmap> = valuesInternal().filterNot(Bitmap::isRecycled)

        fun clearAll() {
            synchronized(aliasLock) {
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
            val aliasKey = synchronized(aliasLock) {
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
            val aliasKey = synchronized(aliasLock) { aliasKeys[name] } ?: return null
            return getInternal(aliasKey)?.takeIf { !it.isRecycled }
        }

        protected abstract fun getInternal(key: PageBitmapKey): Bitmap?
        protected abstract fun putInternal(key: PageBitmapKey, bitmap: Bitmap)
        protected abstract fun valuesInternal(): Collection<Bitmap>
        protected abstract fun clearInternal()
        protected abstract fun cleanUpInternal()
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
        private val lock = Any()
        private val delegate = object : LruCache<PageBitmapKey, Bitmap>(cacheSize) {
            override fun sizeOf(key: PageBitmapKey, value: Bitmap): Int = safeByteCount(value)

            override fun entryRemoved(evicted: Boolean, key: PageBitmapKey, oldValue: Bitmap, newValue: Bitmap?) {
                if (evicted || newValue == null) {
                    recycleBitmap(oldValue)
                }
            }
        }

        override fun getInternal(key: PageBitmapKey): Bitmap? = synchronized(lock) {
            delegate.get(key)
        }

        override fun putInternal(key: PageBitmapKey, bitmap: Bitmap) {
            synchronized(lock) {
                delegate.put(key, bitmap)?.let { previous ->
                    if (previous !== bitmap) {
                        recycleBitmap(previous)
                    }
                }
            }
        }

        override fun valuesInternal(): Collection<Bitmap> = synchronized(lock) {
            delegate.snapshot().values.toList()
        }

        override fun clearInternal() {
            synchronized(lock) {
                delegate.evictAll()
            }
        }

        override fun cleanUpInternal() {
            // LruCache does not require explicit cleanup.
        }
    }
}
