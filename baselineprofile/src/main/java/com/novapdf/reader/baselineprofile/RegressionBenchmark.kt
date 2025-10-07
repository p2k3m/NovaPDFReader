package com.novapdf.reader.baselineprofile

import android.util.Log
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "RegressionBenchmark"
private const val DOCUMENT_LOAD_BUDGET_MS = 6_000L
private const val SCROLL_RENDER_BUDGET_MS = 1_500L

@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class RegressionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @RenderMetric
    @Test
    fun documentLoadWithinBudget() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric("PdfiumRender#0")),
            iterations = 3,
            startupMode = StartupMode.COLD
        ) {
            launchReaderAndAwait()

            val loadDurationMs = measureStressDocumentLoadTimeMs()
            Log.d(TAG, "Document load completed in ${loadDurationMs}ms")

            assertTrue(
                "Document load regression: expected <= $DOCUMENT_LOAD_BUDGET_MS ms but was $loadDurationMs ms",
                loadDurationMs <= DOCUMENT_LOAD_BUDGET_MS
            )
        }
    }

    @FrameRateMetric
    @Test
    fun renderScrollWithinBudget() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.COLD
        ) {
            launchReaderAndAwait()
            openStressDocumentAndAwait()

            val scrollDurationMs = measureTimeMillis {
                exerciseReaderContent()
            }
            Log.d(TAG, "Scroll interaction completed in ${scrollDurationMs}ms")

            assertTrue(
                "Render regression: expected scroll <= $SCROLL_RENDER_BUDGET_MS ms but was $scrollDurationMs ms",
                scrollDurationMs <= SCROLL_RENDER_BUDGET_MS
            )
        }
    }
}
