package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class FrameRateBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @FrameRateMetric
    @Test
    fun scrollDocument() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD
    ) {
        launchReaderAndAwait()
        openStressDocumentAndAwait()
        exerciseReaderContent()
    }

    @FrameRateMetric
    @Test
    fun steadyStateScrollMaintainsFps() {
        val fpsSamples = mutableListOf<Double>()

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD
        ) {
            launchReaderAndAwait()
            openStressDocumentAndAwait()
            val fps = measureSteadyStateScrollFps()
            fpsSamples += fps
        }

        val medianFps = fpsSamples.median()
        val minimumAllowed = STEADY_STATE_SCROLL_TARGET_FPS - STEADY_STATE_SCROLL_VARIANCE_FPS
        assertTrue(
            "Median steady-state scroll FPS regression: expected >= $minimumAllowed fps but was $medianFps fps (target $STEADY_STATE_SCROLL_TARGET_FPS±$STEADY_STATE_SCROLL_VARIANCE_FPS)",
            medianFps >= minimumAllowed
        )
    }
}
