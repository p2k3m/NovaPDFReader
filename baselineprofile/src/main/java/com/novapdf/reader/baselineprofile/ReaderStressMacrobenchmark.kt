package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.novapdf.reader.logging.NovaLog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val STRESS_TAG = "ReaderStressBenchmark"
private const val LOAD_BUDGET_MS = 6_000L

@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class ReaderStressMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @RenderMetric
    @Test
    fun repeatedOpenCloseCycles() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(
                FrameTimingMetric(),
                MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)
            ),
            iterations = 3,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial()
        ) {
            launchReaderAndAwait()
            val loadDurations = repeatOpenCloseStress(iterations = 3)
            loadDurations.forEachIndexed { index, duration ->
                NovaLog.d(
                    STRESS_TAG,
                    "Cycle ${index + 1} document load completed in ${duration}ms"
                )
                assertTrue(
                    "Document load budget exceeded during cycle ${index + 1}: expected <= $LOAD_BUDGET_MS ms but was ${duration}ms",
                    duration <= LOAD_BUDGET_MS
                )
            }
        }
    }

    @RenderMetric
    @Test
    fun cacheHitMissLoadRegressionGuard() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric("PdfiumRender#0")),
            iterations = 3,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial()
        ) {
            launchReaderAndAwait()
            val coldLoad = measureStressDocumentLoadTimeMs()
            NovaLog.d(STRESS_TAG, "Cold cache document load completed in ${coldLoad}ms")
            finishReaderAndReturnHome()

            relaunchReaderAndAwait()
            val warmLoad = measureStressDocumentLoadTimeMs()
            NovaLog.d(STRESS_TAG, "Warm cache document load completed in ${warmLoad}ms")

            assertTrue(
                "Expected warm cache load ($warmLoad ms) to be faster than cold cache load ($coldLoad ms)",
                warmLoad <= coldLoad
            )

            finishReaderAndReturnHome()
        }
    }

    @FrameRateMetric
    @Test
    fun rotationStressMaintainsResponsiveness() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial()
        ) {
            launchReaderAndAwait()
            openStressDocumentAndAwait()
            performOrientationStress()
        }
    }

    @StartupMetric
    @Test
    fun rapidOpenCloseBurst() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial()
        ) {
            launchReaderAndAwait()
            repeatOpenCloseStress(iterations = 5)
        }
    }
}
