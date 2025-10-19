package com.novapdf.reader.data.remote

import android.content.ContentResolver
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

@Singleton
class FileStorageClient @Inject constructor() : StorageClient {

    override fun handles(uri: Uri): Boolean {
        return uri.scheme?.equals(ContentResolver.SCHEME_FILE, ignoreCase = true) == true
    }

    override suspend fun openInputStream(uri: Uri): InputStream {
        if (!handles(uri)) throw UnsupportedStorageUriException(uri)
        val resolvedPath = resolvePath(uri)
        val file = File(resolvedPath)
        return withContext(Dispatchers.IO) {
            if (!file.exists() || !file.isFile) {
                throw IOException("File not found for $uri")
            }
            object : FileInputStream(file), ContentLengthAwareInputStream {
                override val contentLength: Long? = file.length().takeIf { it >= 0L }
            }
        }
    }

    private fun resolvePath(uri: Uri): String {
        val authority = uri.authority
        val path = uri.path
        val resolved = when {
            !authority.isNullOrBlank() -> "/$authority${path ?: ""}"
            else -> path
        }
        return resolved?.takeIf { it.isNotBlank() } ?: throw UnsupportedStorageUriException(uri)
    }
}
