package com.novapdf.reader.storage

import android.content.ContentResolver
import android.net.Uri
import com.novapdf.reader.data.remote.ContentLengthAwareInputStream
import com.novapdf.reader.data.remote.DelegatingStorageClient
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.UnsupportedStorageUriException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Factory methods for storage clients used in local unit tests. */
object TestStorageClients {

    /** Creates a [StorageClient] that reads bytes from the local filesystem using `file://` URIs. */
    fun fileScheme(rootOverride: File? = null): StorageClient = FileUriStorageClient(rootOverride)

    /**
     * Creates a [StorageClient] that resolves `s3://bucket/key` URIs to files stored locally.
     *
     * @param bucketRoots Mapping of bucket name to the directory that contains object keys.
     * @param defaultRoot Optional fallback directory used when a bucket is not explicitly mapped.
     */
    fun localS3(
        bucketRoots: Map<String, File>,
        defaultRoot: File? = null,
    ): StorageClient = LocalS3StorageClient(bucketRoots, defaultRoot)

    /** Combines multiple [StorageClient] instances into a single delegating client. */
    fun composite(vararg clients: StorageClient): StorageClient = DelegatingStorageClient(*clients)
}

private class FileUriStorageClient(
    private val rootOverride: File?
) : StorageClient {

    override fun handles(uri: Uri): Boolean {
        return uri.scheme?.lowercase(Locale.US) == ContentResolver.SCHEME_FILE
    }

    override suspend fun openInputStream(uri: Uri): InputStream {
        if (!handles(uri)) throw UnsupportedStorageUriException(uri)
        val path = uri.path?.takeIf { it.isNotEmpty() }
            ?: throw UnsupportedStorageUriException(uri)
        val file = File(path)
        val target = if (file.isAbsolute || rootOverride == null) file else File(rootOverride, path)
        if (!target.isFile) {
            throw IOException("File not found for $uri")
        }
        return withContext(Dispatchers.IO) {
            object : FileInputStream(target), ContentLengthAwareInputStream {
                override val contentLength: Long? = target.length()
            }
        }
    }
}

private class LocalS3StorageClient(
    private val bucketRoots: Map<String, File>,
    private val defaultRoot: File?,
) : StorageClient {

    override fun handles(uri: Uri): Boolean {
        return uri.scheme?.lowercase(Locale.US) == SCHEME
    }

    override suspend fun openInputStream(uri: Uri): InputStream {
        if (!handles(uri)) throw UnsupportedStorageUriException(uri)
        val bucket = uri.host?.takeIf { it.isNotBlank() } ?: uri.authority
            ?: throw UnsupportedStorageUriException(uri)
        val key = uri.encodedPath?.trimStart('/')?.takeIf { it.isNotEmpty() }
            ?: throw UnsupportedStorageUriException(uri)
        val root = bucketRoots[bucket] ?: defaultRoot
            ?: throw IOException("No local mapping configured for bucket $bucket")
        val target = File(root, key)
        if (!target.isFile) {
            throw IOException("File not found for $uri")
        }
        return withContext(Dispatchers.IO) {
            object : FileInputStream(target), ContentLengthAwareInputStream {
                override val contentLength: Long? = target.length()
            }
        }
    }

    private companion object {
        private const val SCHEME = "s3"
    }
}
