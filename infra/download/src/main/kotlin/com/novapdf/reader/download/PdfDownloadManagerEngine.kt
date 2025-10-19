package com.novapdf.reader.download

import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import kotlinx.coroutines.flow.Flow

/**
 * Adapter that exposes [PdfDownloadManager] as a [RemotePdfDownloadEngine].
 */
class PdfDownloadManagerEngine(
    private val delegate: PdfDownloadManager,
    override val name: String = delegate::class.java.simpleName,
    private val predicate: (String) -> Boolean = { true },
) : RemotePdfDownloadEngine {

    override fun supports(url: String): Boolean = predicate(url)

    override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> {
        return delegate.download(url, allowLargeFile)
    }
}
