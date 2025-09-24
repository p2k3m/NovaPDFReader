package com.novapdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
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
        val manager = AdaptiveFlowManager(context)
        ShadowSystemClock.reset()
        manager.trackPageChange(0, 10)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        manager.trackPageChange(1, 10)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        manager.trackPageChange(2, 10)

        val speed = manager.readingSpeedPagesPerMinute.first()
        assertTrue(speed > 0f)

        val preload = manager.preloadTargets.first()
        assertTrue(preload.isNotEmpty())
    }
}
