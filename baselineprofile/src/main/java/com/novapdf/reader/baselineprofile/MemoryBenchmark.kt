package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class MemoryBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @MemoryMetric
    @Test
    @OptIn(ExperimentalMetricApi::class)
    fun coldStartMemory() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(MemoryUsageMetric(mode = MemoryUsageMetric.Mode.Max)),
        iterations = 3,
        startupMode = StartupMode.COLD
    ) {
        launchReaderAndAwait()
        exerciseReaderContent()
    }
}
