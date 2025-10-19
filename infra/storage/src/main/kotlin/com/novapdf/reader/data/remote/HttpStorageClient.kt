package com.novapdf.reader.data.remote

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

@Singleton
class HttpStorageClient @Inject constructor() : StorageClient {

    override fun handles(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase(Locale.US)) {
            "http", "https" -> true
            else -> false
        }
    }

    override suspend fun openInputStream(uri: Uri): InputStream {
        if (!handles(uri)) throw UnsupportedStorageUriException(uri)
        return withContext(Dispatchers.IO) {
            val connection = (URL(uri.toString()).openConnection() as? HttpURLConnection)
                ?: throw IOException("Unsupported connection for $uri")
            connection.connectTimeout = DEFAULT_CONNECT_TIMEOUT_MS
            connection.readTimeout = DEFAULT_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.connect()
            val stream = connection.inputStream
            val reportedLength = connection.contentLengthLong.takeIf { it >= 0L }
            object : FilterInputStream(stream), ContentLengthAwareInputStream {
                override val contentLength: Long? = reportedLength

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
    }

    private companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 30_000
    }
}
