package com.novapdf.reader

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.collections.buildList
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
        val harnessRequested = shouldRunHarness()
        Log.i(TAG, "Screenshot harness requested=$harnessRequested")
        assumeTrue("Screenshot harness disabled", harnessRequested)

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        handshakePackageName = resolveTestPackageName()
        handshakeCacheDir = resolveHandshakeCacheDir(handshakePackageName)
        Log.i(
            TAG,
            "Using handshake cache directory ${handshakeCacheDir.absolutePath} for package $handshakePackageName"
        )
        harnessEnabled = true
        ensureWorkManagerInitialized(appContext)
        Log.i(TAG, "Installing thousand-page stress document for screenshot harness")
        documentUri = TestDocumentFixtures.installThousandPageDocument(appContext)
        Log.i(TAG, "Thousand-page document installed at ${documentUri}")
        cancelWorkManagerJobs()
    }

    @After
    fun tearDown() = runBlocking {
        if (!harnessEnabled || !::handshakeCacheDir.isInitialized) {
            return@runBlocking
        }
        cancelWorkManagerJobs()
        withContext(Dispatchers.IO) { cleanupFlags() }
    }

    @Test
    fun openThousandPageDocumentForScreenshots() = runBlocking {
        val harnessActive = shouldRunHarness()
        Log.i(TAG, "Screenshot harness active=$harnessActive")
        assumeTrue("Screenshot harness disabled", harnessActive)

        openDocumentInViewer()
        waitForScreenshotHandshake()
    }

    private suspend fun waitForScreenshotHandshake() {
        val readyFlag = File(handshakeCacheDir, SCREENSHOT_READY_FLAG)
        val doneFlag = File(handshakeCacheDir, SCREENSHOT_DONE_FLAG)

        if (!withContext(Dispatchers.IO) { clearFlag(doneFlag) }) {
            Log.w(
                TAG,
                "Unable to clear stale screenshot completion flag at ${doneFlag.absolutePath}; " +
                    "continuing with existing flag"
            )
        }

        Log.i(TAG, "Writing screenshot ready flag to ${readyFlag.absolutePath}")
        withContext(Dispatchers.IO) { writeHandshakeFlag(readyFlag, "ready") }
        Log.i(TAG, "Waiting for screenshot harness completion signal at ${doneFlag.absolutePath}")

        val start = System.currentTimeMillis()
        var lastLog = start
        while (!doneFlag.exists()) {
            if (!activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED)) {
                throw IllegalStateException("ReaderActivity unexpectedly stopped while waiting for screenshots")
            }
            if (System.currentTimeMillis() - start > TimeUnit.MINUTES.toMillis(5)) {
                throw IllegalStateException("Timed out waiting for host screenshot completion signal")
            }
            val now = System.currentTimeMillis()
            if (now - lastLog >= TimeUnit.SECONDS.toMillis(15)) {
                Log.i(TAG, "Screenshot harness still waiting; ready flag present=${readyFlag.exists()} done flag present=${doneFlag.exists()}")
                lastLog = now
            }
            Thread.sleep(250)
        }

        withContext(Dispatchers.IO) {
            deleteHandshakeFlag(doneFlag)
            deleteHandshakeFlag(readyFlag)
        }
        Log.i(TAG, "Screenshot harness handshake completed; flags cleared")
    }

    private fun cleanupFlags() {
        if (!::handshakeCacheDir.isInitialized) {
            return
        }
        Log.i(TAG, "Cleaning up screenshot harness flags in ${handshakeCacheDir.absolutePath}")
        deleteHandshakeFlag(File(handshakeCacheDir, SCREENSHOT_READY_FLAG), failOnError = false)
        deleteHandshakeFlag(File(handshakeCacheDir, SCREENSHOT_DONE_FLAG), failOnError = false)
    }

    private fun writeHandshakeFlag(flag: File, contents: String) {
        flag.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create directory for handshake flag at ${parent.absolutePath}")
            }
        }

        runCatching {
            flag.writeText(contents, Charsets.UTF_8)
        }.onFailure { error ->
            throw IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
        }
    }

    private fun deleteHandshakeFlag(flag: File, failOnError: Boolean = true) {
        if (!flag.exists()) {
            return
        }

        if (!flag.delete()) {
            if (failOnError) {
                throw IllegalStateException("Unable to delete handshake flag at ${flag.absolutePath}")
            } else {
                Log.w(TAG, "Unable to delete handshake flag at ${flag.absolutePath}")
            }
        }
    }

    private fun clearFlag(flag: File): Boolean {
        return if (!flag.exists()) {
            true
        } else {
            flag.delete()
        }
    }

    private fun resolveHandshakeCacheDir(testPackageName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val candidateDirectories = buildList {
            runCatching { findTestPackageCacheDir(testPackageName) }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "Falling back to instrumentation context for screenshot handshake cache directory",
                        error
                    )
                }
                .getOrNull()
                ?.let(::add)

            addAll(cacheCandidatesForContext(instrumentation.context))
        }

        val cacheDir = candidateDirectories
            .firstOrNull(::prepareCacheDirectory)
            ?: throw IllegalStateException(
                "Instrumentation cache directory unavailable for screenshot handshake"
            )

        Log.i(TAG, "Resolved screenshot handshake cache directory ${cacheDir.absolutePath}")

        return cacheDir
    }

    private fun cacheCandidatesForContext(context: Context): List<File> {
        return listOfNotNull(
            context.cacheDir,
            context.codeCacheDir,
            context.credentialProtectedStorageContext().cacheDir
        )
    }

    private fun prepareCacheDirectory(directory: File?): Boolean {
        if (directory == null) {
            return false
        }
        return directory.exists() || directory.mkdirs()
    }

    private fun findTestPackageCacheDir(testPackageName: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageManager = instrumentation.context.packageManager
        val applicationInfo = runCatching { packageManager.getApplicationInfo(testPackageName, 0) }
            .getOrElse { error ->
                val reason = if (error is PackageManager.NameNotFoundException || error is SecurityException) {
                    IllegalStateException(
                        "Unable to resolve application info for screenshot harness package $testPackageName",
                        error
                    )
                } else {
                    error
                }
                throw reason
            }

        val baseDir = applicationInfo.dataDir
            ?: throw IllegalStateException("Missing data directory for screenshot harness package $testPackageName")
        return File(baseDir, "cache")
    }

    private fun resolveTestPackageName(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
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
        if (!::appContext.isInitialized) {
            return
        }
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
                Log.i(TAG, "Cancelling outstanding WorkManager jobs before screenshots")
                manager.cancelAllWork().result.get(WORK_MANAGER_CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Log.i(TAG, "WorkManager jobs cancelled for screenshot harness")
            } catch (error: TimeoutException) {
                Log.w(TAG, "Timed out waiting for WorkManager cancellation during screenshot harness setup", error)
            } catch (error: Exception) {
                Log.w(TAG, "Unexpected failure cancelling WorkManager jobs for screenshot harness", error)
            }
        }
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        val appContext = context.applicationContext
        runCatching { WorkManager.getInstance(appContext) }.onFailure {
            val configuration = Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
            Log.i(TAG, "Initialising test WorkManager for screenshot harness")
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun openDocumentInViewer() {
        Log.i(TAG, "Opening thousand-page document in viewer: $documentUri")
        activityRule.scenario.onActivity { activity ->
            activity.openDocumentForTest(documentUri)
        }

        val deadline = SystemClock.elapsedRealtime() + DOCUMENT_OPEN_TIMEOUT
        var lastLog = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            var documentReady = false
            var errorMessage: String? = null
            var snapshot: PdfViewerUiState? = null
            activityRule.scenario.onActivity { activity ->
                val state = activity.currentDocumentStateForTest()
                snapshot = state
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
                Log.i(TAG, "Thousand-page document finished loading with pageCount=${snapshot?.pageCount}")
                device.waitForIdle()
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastLog >= 5_000L) {
                val state = snapshot
                Log.i(
                    TAG,
                    "Waiting for document to load; status=${state?.documentStatus?.javaClass?.simpleName} " +
                        "pageCount=${state?.pageCount} renderProgress=${state?.renderProgress}"
                )
                lastLog = now
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
