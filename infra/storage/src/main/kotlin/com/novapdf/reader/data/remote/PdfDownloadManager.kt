package com.novapdf.reader.data.remote

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.novapdf.reader.cache.PdfCacheRoot
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.text.Charsets

class PdfDownloadManager(
    context: Context,
    private val storageClient: StorageClient,
    private val authority: String = "${context.packageName}.fileprovider",
) {
    private val appContext = context.applicationContext
    private val downloadDirectory = File(PdfCacheRoot.documents(appContext), "remote").apply { mkdirs() }
    private val pdfiumCore = PdfiumCore(appContext)

    init {
        PdfCacheRoot.ensureSubdirectories(appContext)
    }

    suspend fun download(url: String, allowLargeFile: Boolean = false): Result<Uri> =
        withContext(Dispatchers.IO) {
        val destination = File(downloadDirectory, buildFileName())
        val parsedUri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            null
        }
        if (parsedUri == null || parsedUri.scheme.isNullOrBlank()) {
            destination.delete()
            return@withContext Result.failure(
                RemotePdfException(
                    RemotePdfException.Reason.NETWORK,
                    IllegalArgumentException("Invalid remote URI: $url"),
                )
            )
        }

        val outcome = try {
            storageClient.copyTo(parsedUri, destination)
            validateDownloadedPdf(destination, allowLargeFile)
            val uri = FileProvider.getUriForFile(appContext, authority, destination)
            Result.success(uri)
        } catch (error: Throwable) {
            destination.delete()
            if (error is CancellationException) {
                throw error
            }
            Result.failure(error.asNetworkFailure())
        }
        return@withContext outcome
    }

    private fun buildFileName(): String {
        return "remote_${UUID.randomUUID()}.pdf"
    }

    private fun validateDownloadedPdf(file: File, allowLargeFile: Boolean) {
        val length = file.length()
        if (length <= 0L) {
            throw RemotePdfException(
                RemotePdfException.Reason.CORRUPTED,
                IOException("Downloaded PDF is empty"),
            )
        }

        if (!allowLargeFile && length > MAX_SAFE_PDF_BYTES) {
            throw RemotePdfException(
                RemotePdfException.Reason.FILE_TOO_LARGE,
                RemotePdfTooLargeException(length, MAX_SAFE_PDF_BYTES),
            )
        }

        val unsafeIndicators = detectUnsafePdfIndicators(file)
        if (unsafeIndicators.isNotEmpty()) {
            throw RemotePdfException(
                RemotePdfException.Reason.UNSAFE,
                RemotePdfUnsafeException(unsafeIndicators),
            )
        }

        var descriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            document = pdfiumCore.newDocument(descriptor)
            val pageCount = pdfiumCore.getPageCount(document)
            if (pageCount <= 0) {
                throw RemotePdfException(
                    RemotePdfException.Reason.CORRUPTED,
                    IOException("Downloaded PDF has no pages"),
                )
            }
        } catch (error: Throwable) {
            if (error is RemotePdfException) throw error
            throw RemotePdfException(
                RemotePdfException.Reason.CORRUPTED,
                IOException("Downloaded PDF failed integrity check", error)
            )
        } finally {
            document?.let { doc ->
                runCatching { pdfiumCore.closeDocument(doc) }
            }
            descriptor?.let { pfd ->
                runCatching { pfd.close() }
            }
        }
    }

    private fun Throwable.asNetworkFailure(): RemotePdfException {
        return when (this) {
            is RemotePdfException -> this
            is UnsupportedStorageUriException -> RemotePdfException(RemotePdfException.Reason.NETWORK, this)
            else -> RemotePdfException(RemotePdfException.Reason.NETWORK, this)
        }
    }

    private fun detectUnsafePdfIndicators(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val found = LinkedHashSet<String>()
        val buffer = ByteArray(UNSAFE_SCAN_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            var totalRead = 0L
            while (totalRead < UNSAFE_SCAN_MAX_BYTES && found.size < UNSAFE_PDF_TOKENS.size) {
                val remaining = min(
                    (UNSAFE_SCAN_MAX_BYTES - totalRead).coerceAtLeast(0L),
                    buffer.size.toLong(),
                ).toInt()
                if (remaining <= 0) break
                val read = input.read(buffer, 0, remaining)
                if (read <= 0) break
                totalRead += read
                val chunk = String(buffer, 0, read, Charsets.ISO_8859_1)
                for (token in UNSAFE_PDF_TOKENS) {
                    if (!found.contains(token) && chunk.contains(token, ignoreCase = true)) {
                        found.add(token)
                    }
                }
            }
        }
        return found.toList()
    }

    private companion object {
        private const val MAX_SAFE_PDF_BYTES: Long = 256L * 1024L * 1024L
        private const val UNSAFE_SCAN_MAX_BYTES: Long = 1L * 1024L * 1024L
        private const val UNSAFE_SCAN_BUFFER_SIZE: Int = 64 * 1024
        private val UNSAFE_PDF_TOKENS = listOf(
            "/JavaScript",
            "/JS",
            "/AA",
            "/OpenAction",
            "/Launch",
            "/RichMedia",
            "/AcroForm",
            "/XFA",
            "/EmbeddedFile",
            "/SubmitForm",
            "/GoToR",
        )
    }
}
