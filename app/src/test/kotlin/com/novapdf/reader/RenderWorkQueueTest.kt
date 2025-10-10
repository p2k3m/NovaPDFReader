package com.novapdf.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
