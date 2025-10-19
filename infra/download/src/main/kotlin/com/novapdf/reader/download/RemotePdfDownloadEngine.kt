package com.novapdf.reader.download

import com.novapdf.reader.cache.FallbackController
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

/**
 * Encapsulates a concrete download implementation. Engines are registered with
 * [EngineRemotePdfDownloader] so multiple transports or backends can be swapped at runtime.
 */
interface RemotePdfDownloadEngine {
    val name: String

    fun supports(url: String): Boolean = true

    fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent>
}

/**
 * Coordinates download attempts across one or more [RemotePdfDownloadEngine] implementations while
 * providing retry and fallback semantics.
 */
class EngineRemotePdfDownloader(
    private val engines: List<RemotePdfDownloadEngine>,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialRetryDelayMs: Long = DEFAULT_INITIAL_RETRY_DELAY_MS,
) : RemotePdfDownloader {

    private val fallbackController = FallbackController(onActivated = { /* no-op */ })

    override fun download(url: String, allowLargeFile: Boolean): Flow<RemoteDocumentFetchEvent> = flow {
        val candidates = engines.filter { it.supports(url) }
        if (candidates.isEmpty()) {
            emit(RemoteDocumentFetchEvent.Failure(unsupportedUrl(url)))
            return@flow
        }
        var lastFailure: RemotePdfException? = null
        var immediateFailure: RemotePdfException? = null
        for ((index, engine) in candidates.withIndex()) {
            val isFallback = index > 0
            if (isFallback) {
                fallbackController.activate("engine:${engine.name}")
            }
            val attempt = runEngine(engine, url, allowLargeFile) { event -> emit(event) }
            when (attempt) {
                is EngineAttempt.Success -> {
                    fallbackController.clear()
                    return@flow
                }
                is EngineAttempt.Cancelled -> {
                    fallbackController.clear()
                    throw attempt.error
                }
                is EngineAttempt.TerminalFailure -> {
                    lastFailure = attempt.error
                    if (!attempt.error.isRetryable()) {
                        immediateFailure = attempt.error
                        break
                    }
                }
            }
        }
        fallbackController.clear()
        val failure = immediateFailure
            ?: lastFailure
            ?: RemotePdfException(RemotePdfException.Reason.NETWORK)
        emit(RemoteDocumentFetchEvent.Failure(failure))
    }

    private suspend fun runEngine(
        engine: RemotePdfDownloadEngine,
        url: String,
        allowLargeFile: Boolean,
        emit: suspend (RemoteDocumentFetchEvent) -> Unit,
    ): EngineAttempt {
        var lastFailure: RemotePdfException? = null
        repeat(maxAttempts) { attemptIndex ->
            val attemptNumber = attemptIndex + 1
            var terminalFailure: RemotePdfException? = null
            var succeeded = false
            try {
                withTimeout(attemptTimeoutMs) {
                    engine.download(url, allowLargeFile).collect { event ->
                        when (event) {
                            is RemoteDocumentFetchEvent.Progress -> emit(event)
                            is RemoteDocumentFetchEvent.Success -> {
                                emit(event)
                                succeeded = true
                                return@collect
                            }
                            is RemoteDocumentFetchEvent.Failure -> {
                                val failure = event.error
                                if (failure is CancellationException) throw failure
                                terminalFailure = when (failure) {
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
            } catch (cancelled: CancellationException) {
                return EngineAttempt.Cancelled(cancelled)
            } catch (timeout: TimeoutCancellationException) {
                terminalFailure = RemotePdfException(RemotePdfException.Reason.NETWORK, timeout)
            }

            if (succeeded) {
                return EngineAttempt.Success
            }

            val failure = terminalFailure ?: return EngineAttempt.Success
            lastFailure = failure
            if (!failure.isRetryable() || attemptNumber >= maxAttempts) {
                val finalFailure = if (!failure.isRetryable()) {
                    failure
                } else {
                    RemotePdfException(RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED, failure)
                }
                return EngineAttempt.TerminalFailure(finalFailure)
            }
            delay(computeBackoffDelay(attemptNumber))
        }
        val failure = lastFailure ?: RemotePdfException(RemotePdfException.Reason.NETWORK)
        return EngineAttempt.TerminalFailure(
            RemotePdfException(RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED, failure)
        )
    }

    private fun computeBackoffDelay(attemptNumber: Int): Long {
        val exponential = initialRetryDelayMs shl (attemptNumber - 1)
        val jitterBound = (exponential / 2).coerceAtLeast(1L)
        val jitter = Random.nextLong(0, jitterBound + 1)
        return exponential + jitter
    }

    private fun unsupportedUrl(url: String): RemotePdfException {
        return RemotePdfException(
            RemotePdfException.Reason.NETWORK,
            IllegalArgumentException("No download engine available for $url"),
        )
    }

    private sealed interface EngineAttempt {
        object Success : EngineAttempt
        class TerminalFailure(val error: RemotePdfException) : EngineAttempt
        class Cancelled(val error: CancellationException) : EngineAttempt
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

    companion object {
        internal const val DEFAULT_ATTEMPT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_MAX_ATTEMPTS = 4
        private const val DEFAULT_INITIAL_RETRY_DELAY_MS = 500L
    }
}
