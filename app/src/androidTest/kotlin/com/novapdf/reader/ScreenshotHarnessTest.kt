package com.novapdf.reader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private lateinit var handshakeCacheDir: File
    private var harnessEnabled: Boolean = false

    @Before
    fun setUp() = runBlocking {
        harnessEnabled = shouldRunHarness()
        assumeTrue("Screenshot harness disabled", harnessEnabled)

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        handshakeCacheDir = resolveHandshakeCacheDir()
        documentUri = TestDocumentFixtures.installThousandPageDocument(appContext)
        cancelWorkManagerJobs()
    }

    @After
    fun tearDown() = runBlocking {
        if (!harnessEnabled) return@runBlocking
        cancelWorkManagerJobs()
        withContext(Dispatchers.IO) { cleanupFlags() }
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
            val readyFlag = File(handshakeCacheDir, SCREENSHOT_READY_FLAG)
            val doneFlag = File(handshakeCacheDir, SCREENSHOT_DONE_FLAG)

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
        File(handshakeCacheDir, SCREENSHOT_READY_FLAG).delete()
        File(handshakeCacheDir, SCREENSHOT_DONE_FLAG).delete()
    }

    private fun resolveHandshakeCacheDir(): File {
        val contextCache = InstrumentationRegistry.getInstrumentation().context.cacheDir
            ?: throw IllegalStateException("Instrumentation cache directory unavailable for screenshot handshake")
        if (!contextCache.exists() && !contextCache.mkdirs()) {
            throw IllegalStateException("Unable to create instrumentation cache directory for screenshot handshake")
        }
        return contextCache
    }

    private suspend fun cancelWorkManagerJobs() {
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(appContext)
            try {
                manager.cancelAllWork().result.get(WORK_MANAGER_CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: TimeoutException) {
                Log.w(TAG, "Timed out waiting for WorkManager cancellation during screenshot harness setup", error)
            } catch (error: Exception) {
                Log.w(TAG, "Unexpected failure cancelling WorkManager jobs for screenshot harness", error)
            }
        }
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun openDocumentInViewer() {
        activityRule.scenario.onActivity { activity ->
            activity.openDocumentForTest(documentUri)
        }

        val deadline = SystemClock.elapsedRealtime() + DOCUMENT_OPEN_TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            var documentReady = false
            var errorMessage: String? = null
            activityRule.scenario.onActivity { activity ->
                val state = activity.currentDocumentStateForTest()
                when (val status = state.documentStatus) {
                    is DocumentStatus.Error -> errorMessage = status.message
                    is DocumentStatus.Loading -> documentReady = false
                    DocumentStatus.Idle -> documentReady = state.pageCount > 0
                }
            }

            errorMessage?.let { message ->
                throw IllegalStateException("Failed to load document for screenshots: $message")
            }
            if (documentReady) {
                device.waitForIdle()
                return
            }

            Thread.sleep(250)
        }

        throw IllegalStateException("Timed out waiting for document to finish loading for screenshots")
    }

    private companion object {
        private const val SCREENSHOT_READY_FLAG = "screenshot_ready.flag"
        private const val SCREENSHOT_DONE_FLAG = "screenshot_done.flag"
        private const val HARNESS_ARGUMENT = "runScreenshotHarness"
        // Opening a thousand-page stress document can take a while on CI devices, so give the
        // viewer ample time to finish rendering before failing the harness run.
        private const val DOCUMENT_OPEN_TIMEOUT = 180_000L
        private const val WORK_MANAGER_CANCEL_TIMEOUT_SECONDS = 15L
        private const val TAG = "ScreenshotHarness"
    }
}
