package com.novapdf.reader

import android.content.Context
import android.net.Uri
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

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var documentUri: Uri

    @Inject
    lateinit var testDocumentFixtures: TestDocumentFixtures

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
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
        device.waitForIdle()

        adaptiveFlowManager(appContext).overrideSensitivityForTesting(1.6f)

        val adaptiveFlowActive = device.wait(
            Until.hasObject(By.textContains("Adaptive Flow Active")),
            UI_WAIT_TIMEOUT
        )
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

        val saveButton = device.wait(Until.findObject(By.desc("Save annotations")), UI_WAIT_TIMEOUT)
        assertNotNull("Save annotations action should be visible", saveButton)
        saveButton.click()
        device.waitForIdle()

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
            Until.hasObject(By.textContains("Adaptive Flow")),
            UI_WAIT_TIMEOUT
        )
        assertTrue(
            "Adaptive Flow status chip should appear after opening a document",
            statusVisible
        )
        device.waitForIdle()
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        val appContext = context.applicationContext
        runCatching { WorkManager.getInstance(appContext) }.onFailure {
            val configuration = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
    }

    private companion object {
        private const val UI_WAIT_TIMEOUT = 5_000L
    }
}
