package com.novapdf.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.text.Charsets

@RunWith(AndroidJUnit4::class)
class ScreenshotHarnessTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ReaderActivity::class.java)

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var documentUri: Uri
    private var harnessEnabled: Boolean = false

    @Before
    fun setUp() = runBlocking {
        harnessEnabled = shouldRunHarness()
        assumeTrue("Screenshot harness disabled", harnessEnabled)

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        documentUri = TestDocumentFixtures.installThousandPageDocument(appContext)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        }
    }

    @After
    fun tearDown() = runBlocking {
        if (!harnessEnabled) return@runBlocking
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
            cleanupFlags()
        }
    }

    @Test
    fun openThousandPageDocumentForScreenshots() = runBlocking {
        val harnessActive = shouldRunHarness()
        assumeTrue("Screenshot harness disabled", harnessActive)

        openDocumentInViewer()
        waitForScreenshotHandshake()
    }

    private suspend fun waitForScreenshotHandshake() {
        withContext(Dispatchers.IO) {
            val cacheDir = appContext.cacheDir
                ?: throw IllegalStateException("Cache directory unavailable for screenshot handshake")
            val readyFlag = File(cacheDir, SCREENSHOT_READY_FLAG)
            val doneFlag = File(cacheDir, SCREENSHOT_DONE_FLAG)

            if (doneFlag.exists() && !doneFlag.delete()) {
                throw IllegalStateException("Unable to clear stale screenshot completion flag")
            }

            readyFlag.writeText("ready", Charsets.UTF_8)

            val start = System.currentTimeMillis()
            while (!doneFlag.exists()) {
                if (!activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED)) {
                    throw IllegalStateException("ReaderActivity unexpectedly stopped while waiting for screenshots")
                }
                if (System.currentTimeMillis() - start > TimeUnit.MINUTES.toMillis(5)) {
                    throw IllegalStateException("Timed out waiting for host screenshot completion signal")
                }
                Thread.sleep(250)
            }

            if (!doneFlag.delete()) {
                throw IllegalStateException("Unable to delete screenshot completion flag")
            }
            if (!readyFlag.delete()) {
                throw IllegalStateException("Unable to delete screenshot readiness flag")
            }
        }
    }

    private fun cleanupFlags() {
        val cacheDir = appContext.cacheDir ?: return
        File(cacheDir, SCREENSHOT_READY_FLAG).delete()
        File(cacheDir, SCREENSHOT_DONE_FLAG).delete()
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun openDocumentInViewer() {
        activityRule.scenario.onActivity { activity ->
            activity.openDocumentForTest(documentUri)
        }
        val statusVisible = device.wait(
            Until.hasObject(By.textContains("Adaptive Flow")),
            UI_WAIT_TIMEOUT
        )
        if (!statusVisible) {
            throw IllegalStateException("Adaptive Flow status chip did not appear after opening document")
        }
        device.waitForIdle()
    }

    private companion object {
        private const val SCREENSHOT_READY_FLAG = "screenshot_ready.flag"
        private const val SCREENSHOT_DONE_FLAG = "screenshot_done.flag"
        private const val HARNESS_ARGUMENT = "runScreenshotHarness"
        private const val UI_WAIT_TIMEOUT = 5_000L
    }
}
