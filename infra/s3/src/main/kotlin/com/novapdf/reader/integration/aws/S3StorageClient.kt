package com.novapdf.reader.integration.aws

import android.net.Uri
import com.novapdf.reader.data.remote.ContentLengthAwareInputStream
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.UnsupportedStorageUriException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

@Singleton
class S3StorageClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val awsRequestSigner: AwsRequestSigner,
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
        val unsignedRequest = Request.Builder()
            .url("https://$bucket.s3.amazonaws.com/$key")
            .build()

        return withContext(Dispatchers.IO) {
            val signedRequest = awsRequestSigner.sign(unsignedRequest)
            val response = okHttpClient.newCall(signedRequest).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("S3 request failed with code ${response.code}")
            }
            val body = response.body ?: run {
                response.close()
                throw IOException("S3 response missing body for $uri")
            }
            val stream = body.byteStream()
            val reportedLength = body.contentLength().takeIf { it >= 0L }
            object : FilterInputStream(stream), ContentLengthAwareInputStream {
                override val contentLength: Long? = reportedLength

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        response.close()
                    }
                }
            }
        }
    }

    private companion object {
        private const val SCHEME = "s3"
    }
}
