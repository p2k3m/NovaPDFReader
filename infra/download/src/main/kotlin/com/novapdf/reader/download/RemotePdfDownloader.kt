package com.novapdf.reader.download

import android.net.Uri
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.RemotePdfException

/**
 * Higher level entry point for downloading remote PDF documents. This adapter separates
 * presentation/domain callers from the concrete {@link PdfDownloadManager} implementation in the
 * S3 integration module.
 */
interface RemotePdfDownloader {
    /**
     * Attempts to download the given [url] and returns the resolved [Uri] when successful.
     */
    suspend fun download(url: String): Result<Uri>
}

/**
 * Default [RemotePdfDownloader] that delegates to [PdfDownloadManager].
 */
class S3RemotePdfDownloader(
    private val downloadManager: PdfDownloadManager
) : RemotePdfDownloader {

    override suspend fun download(url: String): Result<Uri> {
        return try {
            downloadManager.download(url)
        } catch (error: RemotePdfException) {
            Result.failure(error)
        }
    }
}
