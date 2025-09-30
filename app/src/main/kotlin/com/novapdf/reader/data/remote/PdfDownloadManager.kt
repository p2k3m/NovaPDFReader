package com.novapdf.reader.data.remote

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfDownloadManager(
    context: Context,
    private val authority: String = "${context.packageName}.fileprovider",
) {
    private val appContext = context.applicationContext
    private val downloadDirectory = File(appContext.cacheDir, "remote_pdfs").apply { mkdirs() }
    private val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .components {
            add(PdfDownloadDecoder.Factory())
        }
        .build()
    private val pdfiumCore = PdfiumCore(appContext)

    suspend fun download(url: String): Result<Uri> = withContext(Dispatchers.IO) {
        val destination = File(downloadDirectory, buildFileName())
        val deferred = CompletableDeferred<File>()
        val payload = PdfDownloadDecoder.Payload(
            destination = destination,
            onDownloaded = { file ->
                if (!deferred.isCompleted) {
                    deferred.complete(file)
                }
            },
            onDownloadFailed = { error ->
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(error)
                }
            }
        )
        val request = ImageRequest.Builder(appContext)
            .data(url)
            .apply {
                extras.set(PdfDownloadDecoder.PAYLOAD_KEY, payload)
            }
            .listener(
                onError = { _: ImageRequest, result: ErrorResult ->
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(result.throwable)
                    }
                }
            )
            .build()

        val executeResult = imageLoader.execute(request)
        val outcome = try {
            val file = deferred.await()
            validateDownloadedPdf(file)
            val uri = FileProvider.getUriForFile(appContext, authority, file)
            Result.success(uri)
        } catch (error: Throwable) {
            destination.delete()
            Result.failure(error)
        }

        if (executeResult is ErrorResult) {
            destination.delete()
        }

        return@withContext outcome
    }

    private fun buildFileName(): String {
        return "remote_${UUID.randomUUID()}.pdf"
    }

    private fun validateDownloadedPdf(file: File) {
        val length = file.length()
        if (length <= 0L) {
            throw IOException("Downloaded PDF is empty")
        }

        var descriptor: ParcelFileDescriptor? = null
        var document: PdfDocument? = null
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            document = pdfiumCore.newDocument(descriptor)
            val pageCount = pdfiumCore.getPageCount(document)
            if (pageCount <= 0) {
                throw IOException("Downloaded PDF has no pages")
            }
        } catch (error: Throwable) {
            throw IOException("Downloaded PDF failed integrity check", error)
        } finally {
            document?.let { doc ->
                runCatching { pdfiumCore.closeDocument(doc) }
            }
            descriptor?.let { pfd ->
                runCatching { pfd.close() }
            }
        }
    }
}
