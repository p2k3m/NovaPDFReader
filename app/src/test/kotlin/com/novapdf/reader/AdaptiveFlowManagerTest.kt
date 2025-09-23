package com.novapdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import org.robolectric.extension.RobolectricExtension
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(RobolectricExtension::class)
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
