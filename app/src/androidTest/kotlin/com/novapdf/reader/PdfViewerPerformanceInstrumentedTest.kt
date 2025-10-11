package com.novapdf.reader

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class PdfViewerPerformanceInstrumentedTest {

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
        cancelOutstandingWork()
    }

    @After
    fun tearDown() = runBlocking {
        resetOrientation()
        cancelOutstandingWork()
    }

    @Test
    fun firstFrameAppearsUnderOneAndHalfSeconds() {
        val elapsed = openDocumentAndAwaitFirstFrame()
        assertTrue(
            "Expected first frame within $FIRST_FRAME_TARGET_MS ms but took $elapsed ms",
            elapsed <= FIRST_FRAME_TARGET_MS
        )
    }

    @Test
    fun scrollingHundredPagesDoesNotTriggerAnr() {
        openDocumentAndAwaitFirstFrame()

        val startX = device.displayWidth * 3 / 4
        val endX = device.displayWidth / 4
        val centerY = device.displayHeight / 2

        var maxObservedPage = 0
        repeat(PAGE_SCROLL_TARGET) {
            val swipeRegistered = device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
            assertTrue("Swipe ${it + 1} should succeed", swipeRegistered)
            device.waitForIdle(SCROLL_IDLE_TIMEOUT_MS)
            activityRule.scenario.onActivity { activity ->
                maxObservedPage = max(
                    maxObservedPage,
                    activity.currentDocumentStateForTest().currentPage
                )
            }
        }

        activityRule.scenario.onActivity { activity ->
            val state = activity.currentDocumentStateForTest()
            assertTrue("Document should still be open", state.pageCount >= PAGE_SCROLL_TARGET)
        }

        assertTrue(
            "Expected to reach at least page ${PAGE_SCROLL_TARGET - 1} but only saw $maxObservedPage",
            maxObservedPage >= PAGE_SCROLL_TARGET - 1
        )
    }

    @Test
    fun orientationChangesWhileScrollingMaintainStability() {
        openDocumentAndAwaitFirstFrame()

        val startX = device.displayWidth * 3 / 4
        val endX = device.displayWidth / 4
        val centerY = device.displayHeight / 2

        repeat(ORIENTATION_SCROLL_BEFORE_ROTATE) {
            assertTrue(
                "Pre-rotation swipe ${it + 1} should succeed",
                device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
            )
        }

        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        device.waitForIdle(SCROLL_IDLE_TIMEOUT_MS)

        repeat(ORIENTATION_SCROLL_DURING_ROTATION) {
            assertTrue(
                "Mid-rotation swipe ${it + 1} should succeed",
                device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
            )
        }

        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        device.waitForIdle(SCROLL_IDLE_TIMEOUT_MS)

        repeat(ORIENTATION_SCROLL_AFTER_ROTATE) {
            assertTrue(
                "Post-rotation swipe ${it + 1} should succeed",
                device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
            )
        }

        activityRule.scenario.onActivity { activity ->
            val state = activity.currentDocumentStateForTest()
            assertTrue("Viewer should remain responsive after rotations", state.pageCount > 0)
            assertTrue(
                "Expected to remain beyond the first page after rotations but was on ${state.currentPage}",
                state.currentPage >= ORIENTATION_SCROLL_BEFORE_ROTATE
            )
        }
    }

    private fun openDocumentAndAwaitFirstFrame(): Long {
        val start = SystemClock.elapsedRealtime()
        activityRule.scenario.onActivity { activity ->
            activity.openDocumentForTest(documentUri)
        }

        val frameVisible = device.wait(
            Until.hasObject(By.textContains("Adaptive Flow")),
            FIRST_FRAME_TIMEOUT_MS
        )
        val elapsed = SystemClock.elapsedRealtime() - start
        assertTrue("Failed to observe first frame within timeout", frameVisible)
        device.waitForIdle(SCROLL_IDLE_TIMEOUT_MS)
        return elapsed
    }

    private fun rotateTo(orientation: Int) {
        activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = orientation
        }
    }

    private fun resetOrientation() {
        activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private suspend fun cancelOutstandingWork() {
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        }
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

    companion object {
        private const val FIRST_FRAME_TARGET_MS = 1_500L
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val SCROLL_IDLE_TIMEOUT_MS = 2_000L
        private const val PAGE_SCROLL_TARGET = 100
        private const val SWIPE_STEPS = 20
        private const val ORIENTATION_SCROLL_BEFORE_ROTATE = 20
        private const val ORIENTATION_SCROLL_DURING_ROTATION = 20
        private const val ORIENTATION_SCROLL_AFTER_ROTATE = 20
    }
}
