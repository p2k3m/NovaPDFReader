package com.novapdf.reader.model

import android.net.Uri

/**
 * Describes progress and results emitted while fetching a remote PDF. Each request should
 * terminate by emitting either [Success] or [Failure].
 */
sealed interface RemoteDocumentFetchEvent {

    /**
     * Reports progress through a specific [stage] of the download pipeline. When known the
     * [fraction] reflects the completion percentage for the stage. Byte counters are provided
     * when the underlying transport exposes them.
     */
    data class Progress(
        val stage: RemoteDocumentStage,
        val fraction: Float? = null,
        val bytesDownloaded: Long? = null,
        val totalBytes: Long? = null,
    ) : RemoteDocumentFetchEvent

    /** Indicates that the remote document resolved successfully to the provided [uri]. */
    data class Success(val uri: Uri) : RemoteDocumentFetchEvent

    /** Indicates that the fetch terminated with [error]. */
    data class Failure(val error: Throwable) : RemoteDocumentFetchEvent
}

/** Enumerates the major stages surfaced while downloading a remote PDF. */
enum class RemoteDocumentStage {
    CONNECTING,
    DOWNLOADING,
    VALIDATING,
}
