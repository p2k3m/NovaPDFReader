package com.novapdf.reader.download

import android.net.Uri
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.RemotePdfException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import javax.inject.Inject

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
class S3RemotePdfDownloader @Inject constructor(
    private val downloadManager: PdfDownloadManager,
) : RemotePdfDownloader {

    override suspend fun download(url: String): Result<Uri> {
        var lastFailure: RemotePdfException? = null

        repeat(MAX_ATTEMPTS) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            val result = try {
                downloadManager.download(url)
            } catch (throwable: Throwable) {
                when (throwable) {
                    is CancellationException -> throw throwable
                    is RemotePdfException -> Result.failure(throwable)
                    else -> Result.failure(
                        RemotePdfException(RemotePdfException.Reason.NETWORK, throwable)
                    )
                }
            }

            if (result.isSuccess) {
                return result
            }

            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val remoteFailure = when (failure) {
                is RemotePdfException -> failure
                else -> RemotePdfException(RemotePdfException.Reason.NETWORK, failure)
            }

            if (!remoteFailure.isRetryable()) {
                return Result.failure(remoteFailure)
            }

            lastFailure = remoteFailure
            if (attemptNumber < MAX_ATTEMPTS) {
                delay(computeBackoffDelay(attemptNumber))
            }
        }

        val finalFailure = lastFailure ?: RemotePdfException(RemotePdfException.Reason.NETWORK)
        return Result.failure(
            RemotePdfException(
                RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED,
                finalFailure,
            )
        )
    }

    private fun RemotePdfException.isRetryable(): Boolean {
        return when (reason) {
            RemotePdfException.Reason.NETWORK -> true
            RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED -> false
            RemotePdfException.Reason.CORRUPTED -> false
        }
    }

    private fun computeBackoffDelay(attemptNumber: Int): Long {
        val exponential = INITIAL_RETRY_DELAY_MS shl (attemptNumber - 1)
        val jitterBound = (exponential / 2).coerceAtLeast(1L)
        val jitter = Random.nextLong(0, jitterBound + 1)
        return exponential + jitter
    }

    private companion object {
        private const val MAX_ATTEMPTS = 4
        private const val INITIAL_RETRY_DELAY_MS = 500L
    }
}
