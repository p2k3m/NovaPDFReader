package com.novapdf.reader.logging

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.Volatile

/**
 * Persists lightweight cache metrics to a per-process artifact for offline debugging.
 * The logger is intentionally best-effort; if the artifact cannot be created the
 * instrumentation simply degrades to a no-op to avoid impacting runtime behavior.
 */
object ProcessMetricsLogger {

    private const val DEFAULT_FILE_PREFIX = "cache-metrics"
    private const val LOGGER_THREAD_NAME = "ProcessMetricsLogger"
    private val installed = AtomicBoolean(false)
    private val lock = Any()
    @Volatile
    private var metricsFile: File? = null
    @Volatile
    private var writeThread: HandlerThread? = null
    @Volatile
    private var writeHandler: Handler? = null
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun install(context: Context, filePrefix: String = DEFAULT_FILE_PREFIX) {
        val appContext = context.applicationContext
        val directory = runCatching { File(appContext.cacheDir, "metrics").apply { mkdirs() } }
            .getOrNull()
            ?: return
        val pid = Process.myPid()
        val processName = runCatching { Application.getProcessName() }.getOrNull()
        val sanitizedName = processName
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^a-zA-Z0-9._-]"), "-")
        val fileName = buildString {
            append(filePrefix.ifBlank { DEFAULT_FILE_PREFIX })
            append('-')
            if (!sanitizedName.isNullOrBlank()) {
                append(sanitizedName)
                append('-')
            }
            append(pid)
            append('.').append("csv")
        }
        val file = File(directory, fileName)
        try {
            if (!file.exists()) {
                file.createNewFile()
            }
            synchronized(lock) {
                if (file.length() == 0L) {
                    FileWriter(file, true).use { writer ->
                        writer.appendLine("timestamp,event,cache,keyHash,keyType,sizeBytes,reason")
                    }
                }
            }
        } catch (_: IOException) {
            disableLogging()
            return
        }

        val thread = HandlerThread(LOGGER_THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND)
        try {
            thread.start()
        } catch (_: Throwable) {
            thread.quitSafely()
            disableLogging()
            return
        }

        val handler = Handler(thread.looper)
        stopWriterThread()
        writeThread = thread
        writeHandler = handler
        metricsFile = file
        installed.set(true)
    }

    fun logCacheHit(cacheName: String, key: Any?, sizeBytes: Int?) {
        log(cacheName, CacheEvent.HIT, key, sizeBytes, reason = null)
    }

    fun logCacheMiss(cacheName: String, key: Any?) {
        log(cacheName, CacheEvent.MISS, key, sizeBytes = null, reason = null)
    }

    fun logCacheEviction(cacheName: String, key: Any?, sizeBytes: Int?, reason: EvictionReason) {
        log(cacheName, CacheEvent.EVICT, key, sizeBytes, reason.name.lowercase(Locale.US))
    }

    private fun log(cacheName: String, event: CacheEvent, key: Any?, sizeBytes: Int?, reason: String?) {
        if (!installed.get()) {
            return
        }
        val handler = writeHandler ?: return
        val file = metricsFile ?: return
        val keyHash = key?.hashCode()?.let { Integer.toHexString(it) } ?: ""
        val keyType = key?.javaClass?.simpleName.orEmpty()
        val sizeField = sizeBytes?.takeIf { it >= 0 }?.toString().orEmpty()
        val reasonField = reason.orEmpty()
        val eventLabel = event.label

        if (!handler.post {
                writeEvent(
                    file = file,
                    cacheName = cacheName,
                    eventLabel = eventLabel,
                    keyHash = keyHash,
                    keyType = keyType,
                    sizeField = sizeField,
                    reasonField = reasonField,
                )
            }
        ) {
            disableLogging()
        }
    }

    private fun writeEvent(
        file: File,
        cacheName: String,
        eventLabel: String,
        keyHash: String,
        keyType: String,
        sizeField: String,
        reasonField: String,
    ) {
        synchronized(lock) {
            val timestamp = timestampFormatter.format(Date())
            val payload = "$timestamp,$eventLabel,$cacheName,$keyHash,$keyType,$sizeField,$reasonField"
            runCatching {
                FileWriter(file, true).use { writer ->
                    writer.appendLine(payload)
                }
            }
        }
    }

    private fun stopWriterThread() {
        writeHandler = null
        writeThread?.quitSafely()
        writeThread = null
    }

    private fun disableLogging() {
        installed.set(false)
        metricsFile = null
        stopWriterThread()
    }

    enum class CacheEvent(internal val label: String) {
        HIT("hit"),
        MISS("miss"),
        EVICT("evict"),
    }

    enum class EvictionReason {
        SIZE,
        MANUAL,
        REPLACED,
        STALE,
    }
}
