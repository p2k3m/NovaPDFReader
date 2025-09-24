package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SampleDocument {
    private const val CACHE_FILE_NAME = "sample.pdf"
    private const val ARG_SAMPLE_URL = "samplePdfUrl"
    private const val SYSTEM_PROPERTY_SAMPLE_URL = "novapdf.samplePdfUrl"

    suspend fun installIntoCache(context: Context): android.net.Uri = withContext(Dispatchers.IO) {
        val sampleUrl = resolveSampleUrl()
            ?: throw IllegalStateException(
                "No sample PDF URL configured. Provide the instrumentation argument \"$ARG_SAMPLE_URL\" or Gradle property \"novapdfSamplePdfUrl\" pointing to an S3 object."
            )

        val appContext = context.applicationContext
        val cacheFile = File(appContext.cacheDir, CACHE_FILE_NAME)
        cacheFile.parentFile?.mkdirs()

        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            downloadToFile(sampleUrl, cacheFile)
        }

        cacheFile.toUri()
    }

    private fun resolveSampleUrl(): String? {
        val instrumentationArgs = InstrumentationRegistry.getArguments()
        val fromArgs = instrumentationArgs.getString(ARG_SAMPLE_URL)?.takeIf { it.isNotBlank() }
        if (fromArgs != null) {
            return fromArgs
        }

        val fromEnv = System.getenv("NOVAPDF_SAMPLE_PDF_URL")?.takeIf { it.isNotBlank() }
        if (fromEnv != null) {
            return fromEnv
        }

        val fromSystemProperty = System.getProperty(SYSTEM_PROPERTY_SAMPLE_URL)?.takeIf { it.isNotBlank() }
        if (fromSystemProperty != null) {
            return fromSystemProperty
        }

        return BuildConfig.SAMPLE_PDF_URL.takeIf { it.isNotBlank() }
    }

    private fun downloadToFile(sourceUrl: String, destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for sample PDF")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val message = buildString {
                    append("Failed to download sample PDF from ")
                    append(sourceUrl)
                    append(". HTTP ")
                    append(responseCode)
                    connection.responseMessage?.takeIf { it.isNotBlank() }?.let { reason ->
                        append(" (")
                        append(reason)
                        append(')')
                    }
                }
                throw IOException(message)
            }

            val tempFile = File(parentDir, destination.name + ".download")
            if (tempFile.exists() && !tempFile.delete()) {
                throw IOException("Unable to clear stale temporary download file")
            }

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (tempFile.length() == 0L) {
                tempFile.delete()
                throw IOException("Downloaded sample PDF is empty")
            }

            if (destination.exists() && !destination.delete()) {
                tempFile.delete()
                throw IOException("Unable to replace cached sample PDF")
            }

            if (!tempFile.renameTo(destination)) {
                tempFile.delete()
                throw IOException("Unable to move downloaded sample PDF into cache")
            }
        } finally {
            connection.disconnect()
        }
    }
}
