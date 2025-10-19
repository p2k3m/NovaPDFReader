package com.novapdf.reader

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.logging.LogField
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class HarnessHealthcheckTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val harnessLoggerRule = ScreenshotHarnessTest.HarnessTestWatcher(
        onEvent = { message -> logHarnessInfo(message) },
        onFailure = { message, error -> logHarnessError(message, error) },
    )

    @Before
    fun setUp() {
        runHarnessEntry("HarnessHealthcheckTest", "setUp") {
            hiltRule.inject()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun harnessDependencyGraph() {
        val harnessRequested = shouldRunHarness()
        logHarnessInfo("Screenshot harness requested=$harnessRequested")
        assumeTrue("Screenshot harness disabled", harnessRequested)

        HarnessReadiness.emit { readinessMarker ->
            println(readinessMarker)
            logHarnessInfo("Harness readiness marker emitted: $readinessMarker")
        }
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun logHarnessInfo(message: String) {
        NovaLog.i(TAG, message)
        println("$TAG: $message")
    }

    private fun logHarnessError(message: String, error: Throwable) {
        val failureFields = mutableListOf<LogField>()
        failureFields += field("component", TAG)
        failureFields.addAll(
            HarnessFailureMetadata.buildFields(
                reason = message,
                context = HarnessFailureContext(
                    testName = TAG,
                    userAction = message,
                ),
                error = error,
            )
        )
        NovaLog.e(tag = TAG, message = message, throwable = error, fields = failureFields.toTypedArray())
        println("$TAG: $message\n${Log.getStackTraceString(error)}")
    }

    private companion object {
        private const val HARNESS_ARGUMENT = "runScreenshotHarness"
        private const val TAG = "ScreenshotHarness"
    }
}
