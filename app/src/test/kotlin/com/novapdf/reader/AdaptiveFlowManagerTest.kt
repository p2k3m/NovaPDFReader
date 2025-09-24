package com.novapdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AdaptiveFlowManagerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun readingSpeedRespondsToPageChanges() = runTest {
        var now = 0L
        val manager = AdaptiveFlowManager(
            context = context,
            wallClock = { now },
            coroutineScope = this
        )
        manager.trackPageChange(0, 10)
        now += 2_000
        manager.trackPageChange(1, 10)
        now += 2_000
        manager.trackPageChange(2, 10)
        manager.updateFrameMetrics(16f)
        advanceUntilIdle()

        val speed = manager.readingSpeedPagesPerMinute.first { it > 0f }
        assertTrue(speed > 0f)

        val preload = manager.preloadTargets.first { it.isNotEmpty() }
        assertTrue(preload.isNotEmpty())
    }

    @Test
    fun frameMetricsReactToJank() = runTest {
        var now = 0L
        val manager = AdaptiveFlowManager(
            context = context,
            wallClock = { now },
            coroutineScope = this
        )
        repeat(5) { manager.updateFrameMetrics(16f) }

        manager.trackPageChange(0, 12)
        now += 1_500
        manager.trackPageChange(1, 12)
        advanceUntilIdle()
        val smoothPreload = manager.preloadTargets.value
        assertTrue(smoothPreload.isNotEmpty())

        repeat(12) { manager.updateFrameMetrics(45f) }
        now += 1_500
        manager.trackPageChange(2, 12)
        advanceUntilIdle()
        val jankyPreload = manager.preloadTargets.value

        assertTrue(jankyPreload.size < smoothPreload.size)
    }
}
