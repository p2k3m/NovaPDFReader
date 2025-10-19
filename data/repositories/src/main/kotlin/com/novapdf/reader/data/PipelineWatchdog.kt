package com.novapdf.reader.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

const val PIPELINE_PROGRESS_TIMEOUT_MS: Long = 15_000L

enum class PipelineType {
    OPEN,
    RENDER,
    INDEX,
}

/**
 * Thrown when a monitored pipeline stops reporting progress for longer than the allowed timeout.
 */
class PipelineTimeoutException(
    val pipeline: PipelineType,
    message: String? = null,
) : CancellationException(message ?: "${pipeline.name.lowercase()} pipeline timed out")

private class PipelineWatchdogElement(
    private val pipeline: PipelineType,
    private val timeoutMillis: Long,
    private val clock: () -> Long,
    private val parentJob: Job,
    scope: CoroutineScope,
    private val onTimeout: (PipelineTimeoutException) -> Unit,
) : ThreadContextElement<PipelineWatchdogElement?>, Closeable {
    private val lastProgressAt = AtomicLong(clock())
    private val monitorJob: Job = scope.launchWatcher()

    override val key: CoroutineContext.Key<PipelineWatchdogElement> = Key

    override fun updateThreadContext(context: CoroutineContext): PipelineWatchdogElement? {
        val previous = currentWatchdog.get()
        currentWatchdog.set(this)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: PipelineWatchdogElement?) {
        currentWatchdog.set(oldState)
    }

    fun notifyProgress() {
        lastProgressAt.set(clock())
    }

    override fun close() {
        monitorJob.cancel()
    }

    private fun CoroutineScope.launchWatcher(): Job {
        return launch(context = coroutineContext, start = CoroutineStart.UNDISPATCHED) {
            try {
                while (isActive && parentJob.isActive) {
                    val elapsed = clock() - lastProgressAt.get()
                    val remaining = timeoutMillis - elapsed
                    if (remaining <= 0L) {
                        val exception = PipelineTimeoutException(pipeline)
                        onTimeout(exception)
                        parentJob.cancel(exception)
                        break
                    }
                    delay(remaining)
                }
            } catch (_: PipelineTimeoutException) {
                // The parent job was cancelled with a timeout exception we propagated above.
            }
        }
    }

    companion object Key : CoroutineContext.Key<PipelineWatchdogElement>
}

suspend fun <T> withPipelineWatchdog(
    pipeline: PipelineType,
    timeoutMillis: Long = PIPELINE_PROGRESS_TIMEOUT_MS,
    clock: () -> Long = SystemClock::elapsedRealtime,
    onTimeout: (PipelineTimeoutException) -> Unit = {},
    block: suspend () -> T,
): T = coroutineScope {
    val element = PipelineWatchdogElement(
        pipeline = pipeline,
        timeoutMillis = timeoutMillis,
        clock = clock,
        parentJob = coroutineContext.job,
        scope = this,
        onTimeout = onTimeout,
    )
    try {
        withContext(element) {
            signalPipelineProgress()
            block()
        }
    } finally {
        element.close()
    }
}

fun signalPipelineProgress() {
    currentWatchdog.get()?.notifyProgress()
}

private val currentWatchdog = ThreadLocal<PipelineWatchdogElement?>()
