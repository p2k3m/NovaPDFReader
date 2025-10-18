package com.novapdf.reader

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.ui.automation.UiAutomatorTags
import com.novapdf.reader.work.DocumentMaintenanceWorker
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class PdfViewerUiAutomatorTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(ReaderActivity::class.java)

    @get:Rule(order = 2)
    val resourceMonitorRule = DeviceResourceMonitorRule(
        contextProvider = { runCatching { ApplicationProvider.getApplicationContext<Context>() }.getOrNull() },
        logger = { message -> Log.i(TAG, message) },
        onResourceExhausted = { reason -> Log.w(TAG, "Resource exhaustion detected: $reason") },
    )

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var documentUri: Uri
    private val targetPackage: String
        get() = appContext.packageName
    private lateinit var deviceTimeouts: DeviceAdaptiveTimeouts
    private var uiWaitTimeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS

    @Inject
    lateinit var testDocumentFixtures: TestDocumentFixtures

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        deviceTimeouts = DeviceAdaptiveTimeouts.forContext(appContext)
        uiWaitTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_UI_WAIT_TIMEOUT_MS,
            min = DEFAULT_UI_WAIT_TIMEOUT_MS,
            max = MAX_UI_WAIT_TIMEOUT_MS,
            allowTightening = false,
        )
        logTestInfo("Using UI wait timeout ${uiWaitTimeoutMs}ms")
        ensureWorkManagerInitialized(appContext)
        documentUri = testDocumentFixtures.installThousandPageDocument(appContext)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun loadsThousandPageDocumentAndActivatesAdaptiveFlow() = runBlocking {
        openDocumentInViewer()

        // Exercise paging by swiping left to move forward.
        val startX = device.displayWidth * 3 / 4
        val endX = device.displayWidth / 4
        val centerY = device.displayHeight / 2
        device.swipe(startX, centerY, endX, centerY, 40)
        device.waitForIdle(uiWaitTimeoutMs)

        adaptiveFlowManager(appContext).overrideSensitivityForTesting(1.6f)

        val statusSelector = adaptiveFlowStatusSelector()
        val chipVisible = device.wait(Until.hasObject(statusSelector), uiWaitTimeoutMs)
        assertTrue(
            "Adaptive Flow status chip should be visible",
            chipVisible
        )

        val activeDescription = appContext.getString(R.string.adaptive_flow_on)
        val adaptiveFlowActive = waitUntil(uiWaitTimeoutMs) {
            device.findObject(statusSelector)?.contentDescription == activeDescription
        }
        assertTrue(
            "Adaptive Flow status chip should report Active",
            adaptiveFlowActive
        )

        activityRule.scenario.onActivity { activity ->
            val state = activity.currentDocumentStateForTest()
            assertEquals(1000, state.pageCount)
        }
    }

    @Test
    fun savingAnnotationsSchedulesImmediateWork() = runBlocking {
        openDocumentInViewer()

        val saveButton = device.wait(
            Until.findObject(By.res(targetPackage, UiAutomatorTags.SAVE_ANNOTATIONS_ACTION)),
            uiWaitTimeoutMs
        )
        assertNotNull("Save annotations action should be visible", saveButton)
        saveButton.click()
        device.waitForIdle(uiWaitTimeoutMs)

        val workInfos = withContext(Dispatchers.IO) {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(DocumentMaintenanceWorker.IMMEDIATE_WORK_NAME)
                .get(5, TimeUnit.SECONDS)
        }
        assertTrue(
            "Immediate autosave work should be enqueued",
            workInfos.isNotEmpty()
        )
        val immediateWork = workInfos.first()
        assertTrue(
            "Immediate autosave work should include the expected tag",
            immediateWork.tags.contains(DocumentMaintenanceWorker.TAG_IMMEDIATE)
        )
    }

    private fun openDocumentInViewer() {
        activityRule.scenario.onActivity { activity ->
            activity.openDocumentForTest(documentUri)
        }
        val statusVisible = device.wait(
            Until.hasObject(adaptiveFlowStatusSelector()),
            uiWaitTimeoutMs
        )
        assertTrue(
            "Adaptive Flow status chip should appear after opening a document",
            statusVisible
        )
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun adaptiveFlowStatusSelector() =
        By.res(targetPackage, UiAutomatorTags.ADAPTIVE_FLOW_STATUS_CHIP)

    private fun ensureWorkManagerInitialized(context: Context) {
        val appContext = context.applicationContext
        runCatching { WorkManager.getInstance(appContext) }.onFailure {
            val configuration = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
    }

    private fun waitUntil(
        timeoutMs: Long = uiWaitTimeoutMs,
        checkIntervalMs: Long = POLL_INTERVAL_MS,
        condition: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            device.waitForIdle(checkIntervalMs)
        }
        return condition()
    }

    private companion object {
        private const val TAG = "PdfViewerUiAutoTest"
        private const val DEFAULT_UI_WAIT_TIMEOUT_MS = 5_000L
        private const val MAX_UI_WAIT_TIMEOUT_MS = 20_000L
        private const val POLL_INTERVAL_MS = 200L
    }

    private fun logTestInfo(message: String) {
        Log.i(TAG, message)
    }
}
