package com.novapdf.reader.download

import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Higher level entry point for downloading remote PDF documents. This adapter separates
 * presentation/domain callers from the concrete {@link PdfDownloadManager} implementation in the
 * S3 integration module.
 */
interface RemotePdfDownloader {
    /**
     * Attempts to download the given [url], emitting structured progress updates and a terminal
     * result.
     */
    fun download(url: String, allowLargeFile: Boolean = false): Flow<RemoteDocumentFetchEvent>
}

/** Default [RemotePdfDownloader] that delegates to [PdfDownloadManager] via the engine layer. */
class S3RemotePdfDownloader @Inject constructor(
    downloadManager: PdfDownloadManager,
) : RemotePdfDownloader {

    private val delegate = EngineRemotePdfDownloader(
        listOf(
            PdfDownloadManagerEngine(
                delegate = downloadManager,
                predicate = { url -> url.startsWith("http", ignoreCase = true) || url.startsWith("s3", ignoreCase = true) }
            )
        )
    )

    override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> {
        return delegate.download(url, allowLargeFile)
    }

    companion object {
        internal const val DOWNLOAD_ATTEMPT_TIMEOUT_MS = EngineRemotePdfDownloader.DEFAULT_ATTEMPT_TIMEOUT_MS
    }
}
