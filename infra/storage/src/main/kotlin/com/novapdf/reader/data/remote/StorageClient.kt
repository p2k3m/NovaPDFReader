package com.novapdf.reader.data.remote

import android.net.Uri
import java.io.IOException
import java.io.InputStream

/**
 * Abstraction over remote object storage providers capable of returning binary content streams
 * for URIs in supported schemes.
 */
interface StorageClient {
    /** Returns true when this client can service the provided [uri]. */
    fun handles(uri: Uri): Boolean

    /** Opens an [InputStream] for the object referenced by [uri]. */
    @Throws(IOException::class)
    suspend fun openInputStream(uri: Uri): InputStream
}

/**
 * Exception thrown when no registered [StorageClient] is capable of resolving the provided URI.
 */
class UnsupportedStorageUriException(uri: Uri) : IOException("Unsupported storage URI: $uri")

/**
 * [StorageClient] that delegates requests to the first client that reports support for the URI.
 */
class DelegatingStorageClient(
    private val delegates: List<StorageClient>,
) : StorageClient {

    constructor(vararg delegates: StorageClient) : this(delegates.toList())

    override fun handles(uri: Uri): Boolean = delegates.any { it.handles(uri) }

    override suspend fun openInputStream(uri: Uri): InputStream {
        val client = delegates.firstOrNull { it.handles(uri) }
            ?: throw UnsupportedStorageUriException(uri)
        return client.openInputStream(uri)
    }
}
