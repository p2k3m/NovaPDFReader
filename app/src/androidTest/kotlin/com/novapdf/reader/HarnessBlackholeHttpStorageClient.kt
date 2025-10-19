package com.novapdf.reader

import android.net.Uri
import android.util.Log
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.UnsupportedStorageUriException
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage client used by the instrumentation harness to blackhole arbitrary HTTP/HTTPS traffic.
 *
 * The viewer only expects remote documents to be fetched from the S3-backed [StorageClient]. Any
 * other network access during harness runs is considered non-deterministic and therefore blocked.
 */
@Singleton
class HarnessBlackholeHttpStorageClient @Inject constructor() : StorageClient {

    override fun handles(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase(Locale.US)) {
            "http", "https" -> true
            else -> false
        }
    }

    override suspend fun openInputStream(uri: Uri): InputStream {
        if (!handles(uri)) throw UnsupportedStorageUriException(uri)
        Log.w(TAG, "Blocked harness network request to $uri")
        throw IOException("Network access blocked by harness: $uri")
    }

    private companion object {
        private const val TAG = "HarnessNetworkBlackhole"
    }
}
