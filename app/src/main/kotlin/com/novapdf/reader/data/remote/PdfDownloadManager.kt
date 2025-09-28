package com.novapdf.reader.data.remote

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import java.io.File
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
            .extras { set(PdfDownloadDecoder.PAYLOAD_KEY, payload) }
            .listener(
                onError = { _, result ->
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(result.throwable)
                    }
                }
            )
            .build()

        val executeResult = imageLoader.execute(request)
        return@withContext try {
            val file = deferred.await()
            val uri = FileProvider.getUriForFile(appContext, authority, file)
            Result.success(uri)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            if (executeResult is coil3.request.ErrorResult) {
                destination.delete()
            }
        }
    }

    private fun buildFileName(): String {
        return "remote_${UUID.randomUUID()}.pdf"
    }
}
