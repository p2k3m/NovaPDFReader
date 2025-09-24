package com.novapdf.reader.data

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import android.util.SparseArray
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val CACHE_BUDGET_BYTES = 50L * 1024L * 1024L

data class PdfDocumentSession(
    val documentId: String,
    val uri: Uri,
    val pageCount: Int,
    val renderer: PdfRenderer,
    val fileDescriptor: ParcelFileDescriptor
)

data class PageTileRequest(
    val pageIndex: Int,
    val tileRect: Rect,
    val scale: Float
)

class PdfDocumentRepository(
    private val context: Context
) {
    private val appContext: Context = context.applicationContext
    private val contentResolver: ContentResolver = context.contentResolver
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Mutex()
    private val maxCacheBytes = calculateCacheBudget()
    private val bitmapCache = AccessOrderBitmapCache(maxCacheBytes)
    private val pageSizeCache = SparseArray<Size>()
    private val pageSizeLock = Mutex()
    private val openSession = MutableStateFlow<PdfDocumentSession?>(null)
    val session: StateFlow<PdfDocumentSession?> = openSession.asStateFlow()
    private val isRendering = AtomicBoolean(false)
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

    fun closeCurrentSession() {
        if (cacheLock.tryLock()) {
            try {
                bitmapCache.clear()
            } finally {
                cacheLock.unlock()
            }
        } else {
            scheduleCacheClear()
        }
        pageSizeCache.clear()
        openSession.value?.let { session ->
            try {
                session.renderer.close()
                session.fileDescriptor.close()
            } catch (_: IOException) {
            }
        }
        openSession.value = null
    }

    suspend fun open(uri: Uri): PdfDocumentSession? {
        closeCurrentSession()
        return withContextGuard {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return@withContextGuard null
            val renderer = PdfRenderer(pfd)
            val session = PdfDocumentSession(
                documentId = uri.toString(),
                uri = uri,
                pageCount = renderer.pageCount,
                renderer = renderer,
                fileDescriptor = pfd
            )
            openSession.value = session
            session
        }
    }

    suspend fun getPageSize(pageIndex: Int): Size? = withContextGuard {
        val session = openSession.value ?: return@withContextGuard null
        pageSizeLock.withLock {
            pageSizeCache[pageIndex]?.let { return@withLock it }
            val page = session.renderer.openPage(pageIndex)
            val size = Size(page.width, page.height)
            page.close()
            pageSizeCache.put(pageIndex, size)
            size
        }
    }

    suspend fun renderTile(request: PageTileRequest): Bitmap? = withContextGuard {
        val session = openSession.value ?: return@withContextGuard null
        val key = cacheKey(request)
        cacheLock.withLock {
            bitmapCache.getBitmap(key)?.let { cached ->
                val config = cached.config ?: Bitmap.Config.ARGB_8888
                return@withContextGuard cached.copy(config, false)
            }
        }
        if (!isRendering.compareAndSet(false, true)) {
            return@withContextGuard null
        }
        try {
            val page = session.renderer.openPage(request.pageIndex)
            val clamped = clampRectToPage(request.tileRect, page.width, page.height)
            if (clamped.isEmpty) {
                page.close()
                return@withContextGuard null
            }
            val width = max(1, (clamped.width() * request.scale).roundToInt())
            val height = max(1, (clamped.height() * request.scale).roundToInt())
            val bitmap = createBitmap(width, height)
            val matrix = Matrix().apply {
                postScale(request.scale, request.scale)
                postTranslate(-clamped.left * request.scale, -clamped.top * request.scale)
            }
            val dest = Rect(0, 0, width, height)
            page.render(bitmap, dest, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            cacheLock.withLock {
                bitmapCache.putBitmap(key, bitmap)
            }
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            bitmap.copy(config, false)
        } finally {
            isRendering.set(false)
        }
    }

    fun preloadTiles(indices: List<Int>, tileFractions: List<RectF>, scale: Float) {
        val session = openSession.value ?: return
        if (tileFractions.isEmpty()) return
        renderScope.launch {
            indices.forEach { pageIndex ->
                if (pageIndex in 0 until session.pageCount) {
                    val pageSize = getPageSizeInternal(pageIndex) ?: return@forEach
                    tileFractions.forEach { fraction ->
                        val rect = fraction.toPageRect(pageSize)
                        if (!rect.isEmpty) {
                            renderTile(PageTileRequest(pageIndex, rect, scale))
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> withContextGuard(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    fun dispose() {
        closeCurrentSession()
        appContext.unregisterComponentCallbacks(componentCallbacks)
        runBlocking {
            cacheLock.withLock {
                bitmapCache.clear()
            }
        }
        renderScope.cancel()
    }

    private fun cacheKey(request: PageTileRequest): String {
        val rect = request.tileRect
        return "${request.pageIndex}_${rect.left},${rect.top},${rect.right},${rect.bottom}_${request.scale.toBits()}"
    }

    private fun clampRectToPage(rect: Rect, width: Int, height: Int): Rect {
        val clamped = Rect(rect)
        val valid = clamped.intersect(0, 0, width, height)
        return if (valid) clamped else Rect()
    }

    private suspend fun getPageSizeInternal(pageIndex: Int): Size? {
        val session = openSession.value ?: return null
        return pageSizeLock.withLock {
            pageSizeCache[pageIndex]?.let { return@withLock it }
            val page = session.renderer.openPage(pageIndex)
            val size = Size(page.width, page.height)
            page.close()
            pageSizeCache.put(pageIndex, size)
            size
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

    private fun scheduleCacheClear() {
        renderScope.launch {
            cacheLock.withLock {
                bitmapCache.clear()
            }
        }
    }

    private inner class AccessOrderBitmapCache(
        private val maxBytes: Long
    ) : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        private val bitmapSizes = HashMap<String, Int>()
        private var cacheSizeBytes = 0L

        fun getBitmap(key: String): Bitmap? {
            val bitmap = super.get(key) ?: return null
            if (bitmap.isRecycled) {
                removeRecycledEntry(key, bitmap)
                return null
            }
            return bitmap
        }

        fun putBitmap(key: String, bitmap: Bitmap) {
            val previous = super.put(key, bitmap)
            previous?.let { onEntryRemoved(key, it) }
            val size = safeByteCount(bitmap)
            bitmapSizes[key] = size
            cacheSizeBytes += size
            trimToBudget()
        }

        private fun trimToBudget() {
            if (cacheSizeBytes <= maxBytes) return
            val iterator = entries.iterator()
            while (cacheSizeBytes > maxBytes && iterator.hasNext()) {
                val entry = iterator.next()
                iterator.remove()
                onEntryRemoved(entry.key, entry.value)
            }
        }

        override fun clear() {
            values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            super.clear()
            bitmapSizes.clear()
            cacheSizeBytes = 0L
        }

        private fun removeRecycledEntry(key: String, bitmap: Bitmap) {
            super.remove(key)
            val size = bitmapSizes.remove(key)?.toLong() ?: safeByteCount(bitmap).toLong()
            cacheSizeBytes = (cacheSizeBytes - size).coerceAtLeast(0L)
        }

        private fun onEntryRemoved(key: String, bitmap: Bitmap) {
            val size = bitmapSizes.remove(key)?.toLong() ?: safeByteCount(bitmap).toLong()
            cacheSizeBytes = (cacheSizeBytes - size).coerceAtLeast(0L)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}
