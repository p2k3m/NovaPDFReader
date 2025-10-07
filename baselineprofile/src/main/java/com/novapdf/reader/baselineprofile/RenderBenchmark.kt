package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@LargeTest
@RunWith(AndroidJUnit4::class)
class RenderBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @RenderMetric
    @Test
    fun renderFirstPage() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(TraceSectionMetric("PdfiumRender#0")),
        iterations = 3,
        startupMode = StartupMode.COLD
    ) {
        launchReaderAndAwait()
        openStressDocumentAndAwait()
    }
}
