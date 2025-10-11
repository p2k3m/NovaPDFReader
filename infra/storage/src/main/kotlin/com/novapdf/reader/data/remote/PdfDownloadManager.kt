package com.novapdf.reader.data.remote

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.novapdf.reader.cache.PdfCacheRoot
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    suspend fun download(url: String): Result<Uri> = withContext(Dispatchers.IO) {
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
            validateDownloadedPdf(destination)
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

    private fun validateDownloadedPdf(file: File) {
        val length = file.length()
        if (length <= 0L) {
            throw RemotePdfException(
                RemotePdfException.Reason.CORRUPTED,
                IOException("Downloaded PDF is empty"),
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
}
