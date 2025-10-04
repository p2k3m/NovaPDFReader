package com.novapdf.reader

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TestDocumentFixtures {
    private const val THOUSAND_PAGE_CACHE = "stress-thousand-pages.pdf"
    private const val THOUSAND_PAGE_URL_ARG = "thousandPagePdfUrl"
    private const val THOUSAND_PAGE_COUNT = 1_000
    private const val THOUSAND_PAGE_ASSET_BASE64 = "thousand_page_fixture.base64"
    private val DEFAULT_THOUSAND_PAGE_URL = BuildConfig.THOUSAND_PAGE_FIXTURE_URL
        .takeIf { it.isNotBlank() }

    suspend fun installThousandPageDocument(context: Context): android.net.Uri =
        withContext(Dispatchers.IO) {
            val candidateDirectories = writableStorageCandidates(context)

            val existing = candidateDirectories
                .map { File(it, THOUSAND_PAGE_CACHE) }
                .firstOrNull { it.exists() && it.length() > 0L }

            if (existing != null) {
                return@withContext existing.toUri()
            }

            val destinationDirectory = candidateDirectories.firstOrNull()
                ?: throw IOException("No writable internal storage directories available for thousand-page PDF")

            val destination = File(destinationDirectory, THOUSAND_PAGE_CACHE)
            destination.parentFile?.mkdirs()
            createThousandPagePdf(destination)
            destination.toUri()
        }

    private suspend fun createThousandPagePdf(destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for thousand-page PDF")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Unable to create cache directory for thousand-page PDF")
        }

        val tempFile = File(parentDir, destination.name + ".tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Unable to clear stale thousand-page PDF cache")
        }

        try {
            val preparedFromAsset = copyBundledThousandPagePdf(tempFile)
            val preparedFromNetwork = if (!preparedFromAsset) {
                tryDownloadThousandPagePdf(tempFile)
            } else {
                false
            }
            if (!preparedFromAsset && !preparedFromNetwork) {
                writeThousandPagePdf(tempFile)
            }
        } catch (error: IOException) {
            tempFile.delete()
            throw error
        }

        if (destination.exists() && !destination.delete()) {
            tempFile.delete()
            throw IOException("Unable to replace cached thousand-page PDF")
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.delete()
            throw IOException("Unable to move thousand-page PDF into cache")
        }
    }

    private fun tryDownloadThousandPagePdf(destination: File): Boolean {
        val url = InstrumentationRegistry.getArguments().getString(THOUSAND_PAGE_URL_ARG)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THOUSAND_PAGE_URL
            ?: return false

        val connection = URL(url).openConnection() as? HttpURLConnection ?: return false

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return false
            }

            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (destination.length() <= 0L) {
                destination.delete()
                return false
            }

            if (!validateThousandPagePdf(destination)) {
                destination.delete()
                return false
            }

            true
        } catch (_: IOException) {
            destination.delete()
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBundledThousandPagePdf(destination: File): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return try {
            instrumentation.context.assets.open(THOUSAND_PAGE_ASSET_BASE64).use { assetStream ->
                Base64InputStream(assetStream, Base64.DEFAULT).use { decodedStream ->
                    destination.outputStream().buffered().use { output ->
                        decodedStream.copyTo(output)
                        output.flush()
                    }
                }
            }
            if (!validateThousandPagePdf(destination)) {
                destination.delete()
                false
            } else {
                true
            }
        } catch (error: IOException) {
            destination.delete()
            Log.w(TAG, "Unable to copy bundled thousand-page PDF fixture", error)
            false
        } catch (error: SecurityException) {
            destination.delete()
            Log.w(TAG, "Security exception copying bundled thousand-page PDF fixture", error)
            false
        }
    }

    private fun validateThousandPagePdf(candidate: File): Boolean {
        return try {
            ParcelFileDescriptor.open(candidate, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.pageCount >= THOUSAND_PAGE_COUNT
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Validation of downloaded thousand-page PDF failed", error)
            false
        }
    }

    private fun writeThousandPagePdf(destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create cache directory for thousand-page PDF")
            }
        } ?: throw IOException("Missing cache directory for thousand-page PDF")

        try {
            destination.outputStream().use { outputStream ->
                ThousandPagePdfWriter(THOUSAND_PAGE_COUNT).writeTo(outputStream)
            }
        } catch (error: IOException) {
            destination.delete()
            throw error
        }

        if (destination.length() <= 0L) {
            destination.delete()
            throw IOException("Failed to generate thousand-page PDF; destination is empty")
        }
    }
    private const val TAG = "TestDocumentFixtures"
}
