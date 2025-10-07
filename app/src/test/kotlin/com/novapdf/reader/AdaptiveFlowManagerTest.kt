package com.novapdf.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import android.hardware.SensorEvent
import android.hardware.SensorManager
import com.novapdf.reader.pdf.engine.DefaultAdaptiveFlowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
        val manager = DefaultAdaptiveFlowManager(
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
        val manager = DefaultAdaptiveFlowManager(
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
        manager.trackPageChange(2, 12)
        advanceUntilIdle()
        val jankyPreload = manager.preloadTargets.value

        assertTrue(jankyPreload.isEmpty())
        assertTrue(manager.uiUnderLoad.value)
    }

    @Test
    fun preloadingResumesAfterCooldown() = runTest {
        var now = 0L
        val manager = DefaultAdaptiveFlowManager(
            context = context,
            wallClock = { now },
            coroutineScope = this
        )

        manager.trackPageChange(0, 10)
        now += 1_000
        manager.trackPageChange(1, 10)
        advanceUntilIdle()
        val baseline = manager.preloadTargets.value
        assertTrue(baseline.isNotEmpty())

        manager.updateFrameMetrics(40f)
        manager.trackPageChange(2, 10)
        advanceUntilIdle()
        assertTrue(manager.preloadTargets.value.isEmpty())
        assertTrue(manager.uiUnderLoad.value)

        now += BuildConfig.ADAPTIVE_FLOW_PRELOAD_COOLDOWN_MS + 200
        manager.trackPageChange(3, 10)
        advanceUntilIdle()

        assertTrue(manager.preloadTargets.value.isNotEmpty())
        assertTrue(!manager.uiUnderLoad.value)
    }

    @Test
    fun sensorTiltBoostsPreloadTargets() = runTest {
        var now = 0L
        val manager = DefaultAdaptiveFlowManager(
            context = context,
            wallClock = { now },
            coroutineScope = this
        )

        manager.trackPageChange(0, 12)
        now += 2_000
        manager.trackPageChange(1, 12)
        advanceUntilIdle()
        val baseline = manager.preloadTargets.value.size

        manager.onSensorChanged(sensorEvent(x = 4f, y = 5f, z = SensorManager.GRAVITY_EARTH))
        now += 2_000
        manager.trackPageChange(2, 12)
        advanceUntilIdle()

        val boosted = manager.preloadTargets.value.size
        assertTrue(boosted >= baseline)
        assertTrue(manager.swipeSensitivity.value > 1f)
    }

    @Test
    fun frameMetricsIgnoreInvalidSamples() {
        val manager = DefaultAdaptiveFlowManager(
            context = context,
            wallClock = { 0L },
            coroutineScope = CoroutineScope(Job() + Dispatchers.Default)
        )

        repeat(5) { manager.updateFrameMetrics(16f) }
        val baseline = manager.frameIntervalMillis.value

        manager.updateFrameMetrics(Float.NaN)
        manager.updateFrameMetrics(0f)
        manager.updateFrameMetrics(1_500f)

        assertEquals(baseline, manager.frameIntervalMillis.value, 0.001f)
    }

    private fun sensorEvent(x: Float, y: Float, z: Float): SensorEvent {
        val constructor = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        val event = constructor.newInstance(3)
        val values = event.values
        values[0] = x
        values[1] = y
        values[2] = z
        return event
    }
}
