package com.novapdf.reader.data.remote

import android.net.Uri
import com.novapdf.reader.cache.FallbackController
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/**
 * Higher-level storage abstraction that allows swapping out backing [StorageClient] instances and
 * layering fallback policies on top.
 */
interface StorageEngine {
    val name: String

    fun canOpen(uri: Uri): Boolean

    @Throws(IOException::class)
    suspend fun open(uri: Uri): InputStream
}

/** Simple [StorageEngine] that forwards calls to a [StorageClient]. */
class StorageClientEngine(
    private val delegate: StorageClient,
    override val name: String = delegate::class.java.simpleName,
) : StorageEngine {

    override fun canOpen(uri: Uri): Boolean = delegate.handles(uri)

    override suspend fun open(uri: Uri): InputStream = delegate.openInputStream(uri)
}

/**
 * Aggregates multiple [StorageEngine] instances and falls back when an engine throws during open.
 */
class DelegatingStorageEngine(
    private val engines: List<StorageEngine>,
) : StorageEngine {

    private val fallback = FallbackController(onActivated = { /* no-op */ })

    constructor(vararg engines: StorageEngine) : this(engines.toList())

    override val name: String = engines.joinToString(separator = ",") { it.name }

    override fun canOpen(uri: Uri): Boolean = engines.any { it.canOpen(uri) }

    override suspend fun open(uri: Uri): InputStream {
        var lastError: IOException? = null
        engines.forEachIndexed { index, engine ->
            if (!engine.canOpen(uri)) {
                return@forEachIndexed
            }
            val isFallback = index > 0
            if (isFallback) {
                fallback.activate("engine:${engine.name}")
            }
            try {
                val stream = engine.open(uri)
                if (isFallback) {
                    fallback.clear()
                }
                return stream
            } catch (cancelled: CancellationException) {
                fallback.clear()
                throw cancelled
            } catch (error: IOException) {
                lastError = error
                // Try next engine.
            }
        }
        fallback.clear()
        throw lastError ?: UnsupportedStorageUriException(uri)
    }
}
