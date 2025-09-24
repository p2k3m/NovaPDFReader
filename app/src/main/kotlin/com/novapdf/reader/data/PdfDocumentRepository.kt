package com.novapdf.reader.data

import android.content.ComponentCallbacks2
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
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

private const val CACHE_BUDGET_BYTES = 50L * 1024L * 1024L

data class PdfDocumentSession(
    val documentId: String,
    val uri: Uri,
    val pageCount: Int,
    val renderer: PdfRenderer,
    val fileDescriptor: ParcelFileDescriptor
)

data class PageRenderRequest(
    val pageIndex: Int,
    val targetSize: Size,
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
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {}
    private val bitmapSizes = HashMap<String, Int>()
    private var cacheSizeBytes = 0L
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
                clearCacheLocked()
            } finally {
                cacheLock.unlock()
            }
        } else {
            scheduleCacheClear()
        }
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

    suspend fun renderPage(request: PageRenderRequest): Bitmap? = withContextGuard {
        val session = openSession.value ?: return@withContextGuard null
        val key = cacheKey(request)
        cacheLock.withLock {
            bitmapCache[key]?.let { cached ->
                if (cached.isRecycled) {
                    removeRecycledEntryLocked(key, cached)
                } else {
                    val config = cached.config ?: Bitmap.Config.ARGB_8888
                    return@withContextGuard cached.copy(config, false)
                }
            }
        }
        if (!isRendering.compareAndSet(false, true)) {
            // Avoid concurrent thrashing; retry once rendering flag clears
            return@withContextGuard null
        }
        try {
            val page = session.renderer.openPage(request.pageIndex)
            val width = max(1, request.targetSize.width)
            val height = max(1, request.targetSize.height)
            val bitmap = createBitmap(width, height)
            val matrix = Matrix().apply { postScale(request.scale, request.scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            cacheLock.withLock {
                putBitmapLocked(key, bitmap)
            }
            val config = bitmap.config ?: Bitmap.Config.ARGB_8888
            bitmap.copy(config, false)
        } finally {
            isRendering.set(false)
        }
    }

    fun preloadPages(indices: List<Int>, size: Size, scale: Float) {
        val session = openSession.value ?: return
        renderScope.launch {
            indices.forEach { pageIndex ->
                if (pageIndex in 0 until session.pageCount) {
                    renderPage(PageRenderRequest(pageIndex, size, scale))
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
                clearCacheLocked()
            }
        }
        renderScope.cancel()
    }

    private fun cacheKey(request: PageRenderRequest): String =
        "${request.pageIndex}_${request.targetSize.width}x${request.targetSize.height}_${request.scale}"

    private fun calculateCacheBudget(): Long {
        val runtimeLimit = Runtime.getRuntime().maxMemory() / 4
        val clamped = min(CACHE_BUDGET_BYTES, runtimeLimit)
        return max(1L, clamped)
    }

    private fun putBitmapLocked(key: String, bitmap: Bitmap) {
        val previous = bitmapCache.put(key, bitmap)
        previous?.let { existing ->
            val previousSize = bitmapSizes.remove(key)?.toLong() ?: safeByteCount(existing).toLong()
            cacheSizeBytes = (cacheSizeBytes - previousSize).coerceAtLeast(0L)
            if (!existing.isRecycled) {
                existing.recycle()
            }
        }
        val size = safeByteCount(bitmap)
        bitmapSizes[key] = size
        cacheSizeBytes += size
        trimToBudgetLocked()
    }

    private fun trimToBudgetLocked() {
        if (cacheSizeBytes <= maxCacheBytes) return
        val iterator = bitmapCache.entries.iterator()
        while (cacheSizeBytes > maxCacheBytes && iterator.hasNext()) {
            val entry = iterator.next()
            iterator.remove()
            val key = entry.key
            val bitmap = entry.value
            val size = bitmapSizes.remove(key)?.toLong() ?: safeByteCount(bitmap).toLong()
            cacheSizeBytes = (cacheSizeBytes - size).coerceAtLeast(0L)
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun clearCacheLocked() {
        bitmapCache.entries.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        bitmapCache.clear()
        bitmapSizes.clear()
        cacheSizeBytes = 0L
    }

    private fun removeRecycledEntryLocked(key: String, bitmap: Bitmap) {
        bitmapCache.remove(key)
        val size = bitmapSizes.remove(key)?.toLong() ?: safeByteCount(bitmap).toLong()
        cacheSizeBytes = (cacheSizeBytes - size).coerceAtLeast(0L)
    }

    private fun safeByteCount(bitmap: Bitmap): Int = try {
        bitmap.byteCount
    } catch (ignored: IllegalStateException) {
        0
    }

    private fun scheduleCacheClear() {
        renderScope.launch {
            cacheLock.withLock {
                clearCacheLocked()
            }
        }
    }
}
