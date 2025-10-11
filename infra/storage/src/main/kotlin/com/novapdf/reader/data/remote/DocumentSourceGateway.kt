package com.novapdf.reader.data.remote

import android.net.Uri
import com.novapdf.reader.model.DocumentSource
import kotlin.jvm.JvmSuppressWildcards

/** Gateway capable of resolving a [DocumentSource] into a readable [Uri]. */
interface DocumentSourceGateway {
    suspend fun fetch(source: DocumentSource): Result<Uri>
}

/** Handler that can resolve a specific [DocumentSource] variant. */
interface DocumentSourceHandler {
    fun canHandle(source: DocumentSource): Boolean
    suspend fun fetch(source: DocumentSource): Result<Uri>
}

class UnsupportedDocumentSourceException(
    val source: DocumentSource,
) : Exception("Unsupported document source: $source")

class DelegatingDocumentSourceGateway(
    private val handlers: Set<@JvmSuppressWildcards DocumentSourceHandler>,
) : DocumentSourceGateway {

    override suspend fun fetch(source: DocumentSource): Result<Uri> {
        val handler = handlers.firstOrNull { it.canHandle(source) }
            ?: return Result.failure(
                RemotePdfException(
                    RemotePdfException.Reason.NETWORK,
                    UnsupportedDocumentSourceException(source),
                )
            )
        return handler.fetch(source)
    }
}
