package com.novapdf.reader.download

import com.novapdf.reader.data.remote.DocumentSourceHandler
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class RemoteUrlDocumentSourceHandler @Inject constructor(
    private val downloader: RemotePdfDownloader,
) : DocumentSourceHandler {

    override fun canHandle(source: DocumentSource): Boolean {
        return source is DocumentSource.RemoteUrl
    }

    override fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent> {
        val remote = source as? DocumentSource.RemoteUrl
            ?: return flowOf(
                RemoteDocumentFetchEvent.Failure(
                    RemotePdfException(
                        RemotePdfException.Reason.NETWORK,
                        IllegalArgumentException("Unsupported document source: $source"),
                    )
                )
            )
        return downloader.download(remote.url, allowLargeFile = remote.allowLargeFile)
    }
}
