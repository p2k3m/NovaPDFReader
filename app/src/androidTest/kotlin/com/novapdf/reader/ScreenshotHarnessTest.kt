package com.novapdf.reader

import android.content.Context
import android.net.Uri
import android.os.Build
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
import java.io.IOException
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
    private lateinit var handshakePackageName: String
    private var harnessEnabled: Boolean = false

    @Before
    fun setUp() = runBlocking {
        harnessEnabled = shouldRunHarness()
        assumeTrue("Screenshot harness disabled", harnessEnabled)

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        handshakePackageName = resolveTestPackageName()
        handshakeCacheDir = resolveHandshakeCacheDir(handshakePackageName)
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

            writeHandshakeFlag(readyFlag, "ready")

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

            deleteHandshakeFlag(doneFlag)
            deleteHandshakeFlag(readyFlag)
        }
    }

    private fun cleanupFlags() {
        deleteHandshakeFlag(File(handshakeCacheDir, SCREENSHOT_READY_FLAG), failOnError = false)
        deleteHandshakeFlag(File(handshakeCacheDir, SCREENSHOT_DONE_FLAG), failOnError = false)
    }

    private fun writeHandshakeFlag(flag: File, contents: String) {
        try {
            flag.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("Unable to create directory for handshake flag at ${parent.absolutePath}")
                }
            }
            flag.writeText(contents, Charsets.UTF_8)
            return
        } catch (error: Exception) {
            Log.w(TAG, "Direct write for handshake flag ${flag.name} failed; falling back to shell command", error)
        }

        val escapedDir = handshakeCacheDir.absolutePath.replace("'", "'\\''")
        val escapedFlag = flag.absolutePath.replace("'", "'\\''")
        val escapedContents = contents.replace("'", "'\\''")

        val command =
            "run-as $handshakePackageName sh -c \"mkdir -p '$escapedDir' && printf '%s' '$escapedContents' > '$escapedFlag'\""

        runShellCommand(command)

        if (!flag.exists()) {
            throw IllegalStateException("Failed to create handshake flag at ${flag.absolutePath}")
        }
    }

    private fun deleteHandshakeFlag(flag: File, failOnError: Boolean = true) {
        if (!flag.exists()) {
            return
        }

        if (flag.delete()) {
            return
        }

        val escapedFlag = flag.absolutePath.replace("'", "'\\''")
        val command = "run-as $handshakePackageName sh -c \"rm -f '$escapedFlag'\""

        runShellCommand(command)

        if (flag.exists()) {
            if (failOnError) {
                throw IllegalStateException("Unable to delete handshake flag at ${flag.absolutePath}")
            } else {
                Log.w(TAG, "Unable to delete handshake flag at ${flag.absolutePath}")
            }
        }
    }

    private fun runShellCommand(command: String) {
        try {
            device.executeShellCommand(command)
        } catch (error: Exception) {
            throw IllegalStateException("Failed to execute shell command for screenshot harness", error)
        }
    }

    private fun resolveHandshakeCacheDir(testPackageName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testCacheDir = runCatching { findTestPackageCacheDir(testPackageName) }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "Falling back to instrumentation context for screenshot handshake cache directory",
                    error
                )
            }
            .getOrNull()

        val cacheDir = testCacheDir ?: instrumentation.context.credentialProtectedStorageContext().cacheDir
            ?: throw IllegalStateException("Instrumentation cache directory unavailable for screenshot handshake")

        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw IllegalStateException("Unable to create cache directory for screenshot handshake at ${cacheDir.absolutePath}")
        }
        return cacheDir
    }

    private fun findTestPackageCacheDir(testPackageName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageManager = instrumentation.targetContext.packageManager
        val applicationInfo = packageManager.getApplicationInfo(testPackageName, 0)

        val baseDir = applicationInfo.dataDir
            ?: throw IllegalStateException("Missing data directory for screenshot harness package $testPackageName")
        return File(baseDir, "cache")
    }

    private fun resolveTestPackageName(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = instrumentation.arguments
        val targetInstrumentation = arguments.getString("targetInstrumentation")

        val parsed = targetInstrumentation
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        if (!parsed.isNullOrEmpty()) {
            return parsed
        }

        return instrumentation.context.packageName
    }

    private fun Context.credentialProtectedStorageContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return this
        }

        val method = runCatching {
            Context::class.java.getMethod("createCredentialProtectedStorageContext")
        }.getOrNull()

        val protectedContext = method?.let { createMethod ->
            runCatching { createMethod.invoke(this) as? Context }.getOrNull()
        }

        return protectedContext ?: this
    }

    private suspend fun cancelWorkManagerJobs() {
        withContext(Dispatchers.IO) {
            val manager = runCatching { WorkManager.getInstance(appContext) }
                .onFailure { error ->
                    if (error is IllegalStateException) {
                        Log.w(
                            TAG,
                            "Skipping WorkManager cancellation for screenshot harness; WorkManager is not initialised",
                            error
                        )
                    } else {
                        Log.w(TAG, "Unable to obtain WorkManager instance for screenshot harness", error)
                    }
                }
                .getOrNull()
                ?: return@withContext
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
