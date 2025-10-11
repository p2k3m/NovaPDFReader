package com.novapdf.reader.download

import android.net.Uri
import com.novapdf.reader.data.remote.DocumentSourceHandler
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.DocumentSource
import javax.inject.Inject

class RemoteUrlDocumentSourceHandler @Inject constructor(
    private val downloader: RemotePdfDownloader,
) : DocumentSourceHandler {

    override fun canHandle(source: DocumentSource): Boolean {
        return source is DocumentSource.RemoteUrl
    }

    override suspend fun fetch(source: DocumentSource): Result<Uri> {
        val remote = source as? DocumentSource.RemoteUrl
            ?: return Result.failure(
                RemotePdfException(
                    RemotePdfException.Reason.NETWORK,
                    IllegalArgumentException("Unsupported document source: $source"),
                )
            )
        return downloader.download(remote.url)
    }
}
