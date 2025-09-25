package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.ExperimentalBaselineProfilesApi
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @OptIn(ExperimentalBaselineProfilesApi::class)
    @Test
    fun generate() = baselineProfileRule.collectBaselineProfile(
        packageName = TARGET_PACKAGE
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }

    private companion object {
        const val TARGET_PACKAGE = "com.novapdf.reader"
    }
}
