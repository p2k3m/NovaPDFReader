package com.novapdf.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderWorkQueueTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun priorityOrderingPrefersVisibleThenNearbyThenThumbnails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val queue = RenderWorkQueue(scope, dispatcher, parallelism = 1)

        val executionOrder = mutableListOf<String>()
        val tasks = listOf(
            async { queue.submit(RenderWorkQueue.Priority.THUMBNAIL) { executionOrder += "thumb-1"; "thumb-1" } },
            async { queue.submit(RenderWorkQueue.Priority.THUMBNAIL) { executionOrder += "thumb-2"; "thumb-2" } },
            async { queue.submit(RenderWorkQueue.Priority.NEARBY_PAGE) { executionOrder += "nearby"; "nearby" } },
            async { queue.submit(RenderWorkQueue.Priority.VISIBLE_PAGE) { executionOrder += "visible"; "visible" } },
        )

        advanceUntilIdle()
        tasks.awaitAll()

        assertEquals(
            listOf("visible", "nearby", "thumb-1", "thumb-2"),
            executionOrder
        )

        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pausingBackgroundWorkCancelsActiveBackgroundJobs() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val queue = RenderWorkQueue(scope, dispatcher, parallelism = 2)

        val visible = async { queue.submit(RenderWorkQueue.Priority.VISIBLE_PAGE) { "visible" } }
        val keepAlive = CompletableDeferred<Unit>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val backgroundCancelled = CompletableDeferred<Boolean>()

        val background = async {
            queue.submit(RenderWorkQueue.Priority.THUMBNAIL) {
                backgroundStarted.complete(Unit)
                try {
                    keepAlive.await()
                    "background"
                } finally {
                    backgroundCancelled.complete(true)
                }
            }
        }

        advanceUntilIdle()
        assertEquals("visible", visible.await())
        backgroundStarted.await()

        queue.setBackgroundWorkEnabled(false)
        advanceUntilIdle()

        assertTrue(background.isCancelled)
        assertTrue(backgroundCancelled.await())

        scope.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pausedBackgroundWorkResumesWhenReenabled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val queue = RenderWorkQueue(scope, dispatcher, parallelism = 1)

        val executionOrder = mutableListOf<String>()
        val background = async {
            queue.submit(RenderWorkQueue.Priority.THUMBNAIL) {
                executionOrder += "thumbnail"
                "thumbnail"
            }
        }

        queue.setBackgroundWorkEnabled(false)
        advanceUntilIdle()

        assertTrue(executionOrder.isEmpty())

        queue.setBackgroundWorkEnabled(true)
        advanceUntilIdle()

        assertEquals(listOf("thumbnail"), executionOrder)
        background.await()

        scope.cancel()
    }
}
