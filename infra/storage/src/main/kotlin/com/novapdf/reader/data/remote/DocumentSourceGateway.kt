package com.novapdf.reader.data.remote

import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.data.remote.RemotePdfException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.jvm.JvmSuppressWildcards

/** Gateway capable of resolving a [DocumentSource] into a readable [Uri]. */
interface DocumentSourceGateway {
    fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent>
}

/** Handler that can resolve a specific [DocumentSource] variant. */
interface DocumentSourceHandler {
    fun canHandle(source: DocumentSource): Boolean
    fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent>
}

/** Raised when no handler claims the provided [DocumentSource]. */
class UnsupportedDocumentSourceException(
    val source: DocumentSource,
) : Exception("Unsupported document source: $source")

class DelegatingDocumentSourceGateway(
    private val handlers: Set<@JvmSuppressWildcards DocumentSourceHandler>,
) : DocumentSourceGateway {

    override fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent> {
        val handler = handlers.firstOrNull { it.canHandle(source) }
            ?: return flowOf(
                RemoteDocumentFetchEvent.Failure(
                    RemotePdfException(
                        RemotePdfException.Reason.NETWORK,
                        UnsupportedDocumentSourceException(source),
                    )
                )
            )
        return handler.fetch(source)
    }
}
