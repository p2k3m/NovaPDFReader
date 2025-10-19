package com.novapdf.reader.download

import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
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

/**
 * Default [RemotePdfDownloader] that delegates to [PdfDownloadManager].
 */
class S3RemotePdfDownloader @Inject constructor(
    private val downloadManager: PdfDownloadManager,
) : RemotePdfDownloader {

    override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> =
        flow {
            var lastFailure: RemotePdfException? = null

            repeat(MAX_ATTEMPTS) { attemptIndex ->
                val attemptNumber = attemptIndex + 1
                var attemptFailure: RemotePdfException? = null
                var attemptSucceeded = false

                try {
                    withTimeout(DOWNLOAD_ATTEMPT_TIMEOUT_MS) {
                        downloadManager.download(url, allowLargeFile).collect { event ->
                            when (event) {
                                is RemoteDocumentFetchEvent.Progress -> emit(event)
                                is RemoteDocumentFetchEvent.Success -> {
                                    emit(event)
                                    attemptSucceeded = true
                                    return@collect
                                }
                                is RemoteDocumentFetchEvent.Failure -> {
                                    val failure = event.error
                                    if (failure is CancellationException) throw failure
                                    attemptFailure = when (failure) {
                                        is RemotePdfException -> failure
                                        else -> RemotePdfException(
                                            RemotePdfException.Reason.NETWORK,
                                            failure,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    when (error) {
                        is TimeoutCancellationException -> {
                            attemptFailure = RemotePdfException(
                                RemotePdfException.Reason.NETWORK,
                                error,
                            )
                        }
                        is CancellationException -> throw error
                        else -> throw error
                    }
                }

                if (attemptSucceeded) {
                    return@flow
                }

                val failure = attemptFailure
                if (failure == null) {
                    // Flow terminated without success or failure; treat as cancellation.
                    return@flow
                }

                if (!failure.isRetryable()) {
                    emit(RemoteDocumentFetchEvent.Failure(failure))
                    return@flow
                }

                lastFailure = failure
                if (attemptNumber < MAX_ATTEMPTS) {
                    delay(computeBackoffDelay(attemptNumber))
                }
            }

            val finalFailure = lastFailure ?: RemotePdfException(RemotePdfException.Reason.NETWORK)
            emit(
                RemoteDocumentFetchEvent.Failure(
                    RemotePdfException(
                        RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED,
                        finalFailure,
                    )
                )
            )
        }

    private fun RemotePdfException.isRetryable(): Boolean {
        return when (reason) {
            RemotePdfException.Reason.NETWORK -> true
            RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED -> false
            RemotePdfException.Reason.CORRUPTED -> false
            RemotePdfException.Reason.CIRCUIT_OPEN -> false
            RemotePdfException.Reason.UNSAFE -> false
            RemotePdfException.Reason.FILE_TOO_LARGE -> false
        }
    }

    private fun computeBackoffDelay(attemptNumber: Int): Long {
        val exponential = INITIAL_RETRY_DELAY_MS shl (attemptNumber - 1)
        val jitterBound = (exponential / 2).coerceAtLeast(1L)
        val jitter = Random.nextLong(0, jitterBound + 1)
        return exponential + jitter
    }

    companion object {
        internal const val DOWNLOAD_ATTEMPT_TIMEOUT_MS = 10_000L
        private const val MAX_ATTEMPTS = 4
        private const val INITIAL_RETRY_DELAY_MS = 500L
    }
}
