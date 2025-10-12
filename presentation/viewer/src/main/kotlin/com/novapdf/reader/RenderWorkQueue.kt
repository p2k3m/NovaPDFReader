package com.novapdf.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates render work to ensure interactive requests remain responsive while
 * background thumbnails are opportunistically processed. Requests are prioritized
 * according to [Priority] and executed with bounded parallelism.
 */
data class RenderQueueStats(
    val active: Int = 0,
    val visible: Int = 0,
    val nearby: Int = 0,
    val thumbnail: Int = 0,
) {
    val totalQueued: Int
        get() = visible + nearby + thumbnail
}

class RenderWorkQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val parallelism: Int,
) {
    enum class Priority {
        VISIBLE_PAGE,
        NEARBY_PAGE,
        THUMBNAIL,
    }

    private data class WorkItem(
        val priority: Priority,
        val block: suspend () -> Any?,
        val deferred: CompletableDeferred<Any?>,
    ) {
        var job: Job? = null
    }

    private val mutex = Mutex()
    private val visibleQueue = ArrayDeque<WorkItem>()
    private val nearbyQueue = ArrayDeque<WorkItem>()
    private val thumbnailQueue = ArrayDeque<WorkItem>()
    private val activeItems = mutableSetOf<WorkItem>()
    private var activeCount = 0
    private var backgroundWorkEnabled = true

    private val _stats = MutableStateFlow(RenderQueueStats())
    val stats: StateFlow<RenderQueueStats> = _stats.asStateFlow()

    suspend fun <T> submit(priority: Priority, block: suspend () -> T): T {
        val deferred = CompletableDeferred<Any?>()
        val item = WorkItem(priority, block = { block() }, deferred)
        deferred.invokeOnCompletion { throwable ->
            if (throwable is CancellationException) {
                handleCancellation(item)
            }
        }
        enqueue(item)
        return try {
            @Suppress("UNCHECKED_CAST")
            deferred.await() as T
        } catch (cancellation: CancellationException) {
            deferred.cancel()
            throw cancellation
        }
    }

    private suspend fun enqueue(item: WorkItem) {
        val toLaunch = mutableListOf<WorkItem>()
        mutex.withLock {
            queueFor(item.priority).addLast(item)
            fillLaunchListLocked(toLaunch)
            updateStatsLocked()
        }
        toLaunch.forEach { startItem(it) }
    }

    private fun queueFor(priority: Priority): ArrayDeque<WorkItem> = when (priority) {
        Priority.VISIBLE_PAGE -> visibleQueue
        Priority.NEARBY_PAGE -> nearbyQueue
        Priority.THUMBNAIL -> thumbnailQueue
    }

    private fun fillLaunchListLocked(output: MutableList<WorkItem>) {
        while (activeCount < parallelism) {
            val next = pollNextLocked() ?: break
            activeCount++
            activeItems += next
            output += next
        }
    }

    private fun pollNextLocked(): WorkItem? = when {
        visibleQueue.isNotEmpty() -> visibleQueue.removeFirst()
        !backgroundWorkEnabled -> null
        nearbyQueue.isNotEmpty() -> nearbyQueue.removeFirst()
        thumbnailQueue.isNotEmpty() -> thumbnailQueue.removeFirst()
        else -> null
    }

    private fun startItem(item: WorkItem) {
        val job = scope.launch(dispatcher) {
            try {
                val result = item.block()
                item.deferred.complete(result)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    item.deferred.cancel(throwable)
                } else {
                    item.deferred.completeExceptionally(throwable)
                }
            }
        }
        item.job = job
        job.invokeOnCompletion {
            scope.launch(dispatcher) {
                val toLaunch = mutableListOf<WorkItem>()
                mutex.withLock {
                    activeItems.remove(item)
                    activeCount--
                    fillLaunchListLocked(toLaunch)
                    updateStatsLocked()
                }
                toLaunch.forEach { startItem(it) }
            }
        }
    }

    private fun handleCancellation(item: WorkItem) {
        scope.launch(dispatcher) {
            val toLaunch = mutableListOf<WorkItem>()
            val jobToCancel = mutex.withLock {
                val job = item.job
                if (job == null) {
                    removeFromQueuesLocked(item)
                    fillLaunchListLocked(toLaunch)
                }
                updateStatsLocked()
                job
            }
            toLaunch.forEach { startItem(it) }
            jobToCancel?.cancel()
        }
    }

    private fun removeFromQueuesLocked(target: WorkItem) {
        when (target.priority) {
            Priority.VISIBLE_PAGE -> visibleQueue.remove(target)
            Priority.NEARBY_PAGE -> nearbyQueue.remove(target)
            Priority.THUMBNAIL -> thumbnailQueue.remove(target)
        }
    }

    private fun updateStatsLocked() {
        _stats.value = RenderQueueStats(
            active = activeCount,
            visible = visibleQueue.size,
            nearby = nearbyQueue.size,
            thumbnail = thumbnailQueue.size,
        )
    }

    fun setBackgroundWorkEnabled(enabled: Boolean) {
        scope.launch(dispatcher) {
            val toLaunch = mutableListOf<WorkItem>()
            val toCancel = mutableListOf<Job>()
            var changed = false
            mutex.withLock {
                if (backgroundWorkEnabled != enabled) {
                    backgroundWorkEnabled = enabled
                    changed = true
                    if (!enabled) {
                        activeItems
                            .filter { it.priority != Priority.VISIBLE_PAGE }
                            .mapNotNullTo(toCancel) { it.job }
                    } else {
                        fillLaunchListLocked(toLaunch)
                    }
                    updateStatsLocked()
                }
            }
            if (!changed) return@launch
            toCancel.forEach { it.cancel() }
            toLaunch.forEach { startItem(it) }
        }
    }
}
