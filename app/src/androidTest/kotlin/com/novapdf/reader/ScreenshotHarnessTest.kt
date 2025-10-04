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
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlin.text.Charsets

@RunWith(AndroidJUnit4::class)
class ScreenshotHarnessTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ReaderActivity::class.java)

    @get:Rule
    val harnessLoggerRule = HarnessTestWatcher(
        onEvent = { message -> logHarnessInfo(message) },
        onFailure = { message, error -> logHarnessError(message, error) }
    )

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var documentUri: Uri
    private lateinit var handshakeCacheDirs: List<File>
    private lateinit var handshakePackageName: String
    private var harnessEnabled: Boolean = false

    @Before
    fun setUp() = runBlocking {
        val harnessRequested = shouldRunHarness()
        logHarnessInfo("Screenshot harness requested=$harnessRequested")
        assumeTrue("Screenshot harness disabled", harnessRequested)

        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        handshakePackageName = resolveTestPackageName()
        logHarnessInfo("Resolved screenshot harness package name: $handshakePackageName")
        handshakeCacheDirs = resolveHandshakeCacheDirs(handshakePackageName)
        logHarnessInfo(
            "Using handshake cache directories ${handshakeCacheDirs.joinToString { it.absolutePath }} " +
                "for package $handshakePackageName"
        )
        harnessEnabled = true
        ensureWorkManagerInitialized(appContext)
        logHarnessInfo("Installing thousand-page stress document for screenshot harness")
        documentUri = TestDocumentFixtures.installThousandPageDocument(appContext)
        logHarnessInfo("Thousand-page document installed at $documentUri")
        cancelWorkManagerJobs()
    }

    @After
    fun tearDown() = runBlocking {
        if (!harnessEnabled || !::handshakeCacheDirs.isInitialized) {
            return@runBlocking
        }
        cancelWorkManagerJobs()
        withContext(Dispatchers.IO) { cleanupFlags() }
    }

    @Test
    fun openThousandPageDocumentForScreenshots() = runBlocking {
        val harnessActive = shouldRunHarness()
        logHarnessInfo("Screenshot harness active=$harnessActive")
        assumeTrue("Screenshot harness disabled", harnessActive)

        openDocumentInViewer()
        waitForScreenshotHandshake()
    }

    private suspend fun waitForScreenshotHandshake() {
        val readyFlags = handshakeCacheDirs.map { directory ->
            File(directory, SCREENSHOT_READY_FLAG)
        }
        val doneFlags = handshakeCacheDirs.map { directory ->
            File(directory, SCREENSHOT_DONE_FLAG)
        }

        withContext(Dispatchers.IO) {
            doneFlags.forEach { flag ->
                if (!clearFlag(flag)) {
                    logHarnessWarn(
                        "Unable to clear stale screenshot completion flag at ${flag.absolutePath}; continuing with existing flag"
                    )
                }
            }
        }

        readyFlags.forEach { flag ->
            logHarnessInfo("Writing screenshot ready flag to ${flag.absolutePath}")
        }
        withContext(Dispatchers.IO) {
            readyFlags.forEach { flag ->
                runCatching { writeHandshakeFlag(flag, "ready") }
                    .onFailure { error ->
                        logHarnessWarn(
                            "Unable to write screenshot ready flag to ${flag.absolutePath}; continuing without this location",
                            error
                        )
                    }
            }
        }
        logHarnessInfo(
            "Waiting for screenshot harness completion signal at ${doneFlags.joinToString { it.absolutePath }}"
        )

        val start = System.currentTimeMillis()
        var lastLog = start
        var completedFlag: File? = null
        while (completedFlag == null) {
            if (!activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED)) {
                val error = IllegalStateException(
                    "ReaderActivity unexpectedly stopped while waiting for screenshots"
                )
                logHarnessError(error.message ?: "ReaderActivity stopped unexpectedly", error)
                throw error
            }
            if (System.currentTimeMillis() - start > TimeUnit.MINUTES.toMillis(5)) {
                val error = IllegalStateException("Timed out waiting for host screenshot completion signal")
                logHarnessError(error.message ?: "Timed out waiting for screenshot completion", error)
                throw error
            }
            completedFlag = doneFlags.firstOrNull(File::exists)
            val now = System.currentTimeMillis()
            if (now - lastLog >= TimeUnit.SECONDS.toMillis(15)) {
                logHarnessInfo(
                    "Screenshot harness still waiting; ready flags present=${readyFlags.count(File::exists)} " +
                        "done flags present=${doneFlags.count(File::exists)}"
                )
                lastLog = now
            }
            Thread.sleep(250)
        }

        withContext(Dispatchers.IO) {
            doneFlags.forEach { flag ->
                deleteHandshakeFlag(flag, failOnError = flag == completedFlag)
            }
            readyFlags.forEach { flag ->
                deleteHandshakeFlag(flag, failOnError = false)
            }
        }
        logHarnessInfo("Screenshot harness handshake completed; flags cleared")
    }

    private fun cleanupFlags() {
        if (!::handshakeCacheDirs.isInitialized) {
            return
        }
        handshakeCacheDirs.forEach { directory ->
            logHarnessInfo("Cleaning up screenshot harness flags in ${directory.absolutePath}")
            deleteHandshakeFlag(File(directory, SCREENSHOT_READY_FLAG), failOnError = false)
            deleteHandshakeFlag(File(directory, SCREENSHOT_DONE_FLAG), failOnError = false)
        }
    }

    private fun writeHandshakeFlag(flag: File, contents: String) {
        flag.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                val error = IOException(
                    "Unable to create directory for handshake flag at ${parent.absolutePath}"
                )
                logHarnessError(error.message ?: "Unable to create handshake directory", error)
                throw error
            }
        }

        runCatching {
            flag.writeText(contents, Charsets.UTF_8)
        }.onFailure { error ->
            val wrapped = IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
            logHarnessError(wrapped.message ?: "Failed to create handshake flag", wrapped)
            throw wrapped
        }
    }

    private fun deleteHandshakeFlag(flag: File, failOnError: Boolean = true) {
        if (!flag.exists()) {
            return
        }

        if (!flag.delete()) {
            if (failOnError) {
                val error = IllegalStateException(
                    "Unable to delete handshake flag at ${flag.absolutePath}"
                )
                logHarnessError(error.message ?: "Unable to delete handshake flag", error)
                throw error
            } else {
                logHarnessWarn("Unable to delete handshake flag at ${flag.absolutePath}")
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

    private fun resolveHandshakeCacheDirs(testPackageName: String): List<File> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val candidateDirectories = buildList {
            runCatching { findTestPackageCacheDir(testPackageName) }
                .onFailure { error ->
                    logHarnessWarn(
                        "Falling back to instrumentation context for screenshot handshake cache directory",
                        error
                    )
                }
                .getOrNull()
                ?.let(::add)

            addAll(cacheCandidatesForContext(instrumentation.context))
        }

        val resolvedDirectories = candidateDirectories
            .filter(::prepareCacheDirectory)
            .mapNotNull { directory ->
                runCatching { directory.canonicalFile }.getOrElse { directory }
            }
            .distinctBy { it.absolutePath }

        if (resolvedDirectories.isEmpty()) {
            throw IllegalStateException(
                "Instrumentation cache directory unavailable for screenshot handshake"
            )
        }

        logHarnessInfo(
            "Resolved screenshot handshake cache directories ${resolvedDirectories.joinToString { it.absolutePath }}"
        )

        return resolvedDirectories
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
                        logHarnessWarn(
                            "Skipping WorkManager cancellation for screenshot harness; WorkManager is not initialised",
                            error
                        )
                    } else {
                        logHarnessWarn(
                            "Unable to obtain WorkManager instance for screenshot harness",
                            error
                        )
                    }
                }
                .getOrNull()
                ?: return@withContext
            try {
                logHarnessInfo("Cancelling outstanding WorkManager jobs before screenshots")
                manager.cancelAllWork().result.get(WORK_MANAGER_CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                logHarnessInfo("WorkManager jobs cancelled for screenshot harness")
            } catch (error: TimeoutException) {
                logHarnessWarn(
                    "Timed out waiting for WorkManager cancellation during screenshot harness setup",
                    error
                )
            } catch (error: Exception) {
                logHarnessWarn(
                    "Unexpected failure cancelling WorkManager jobs for screenshot harness",
                    error
                )
            }
        }
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        val appContext = context.applicationContext
        runCatching { WorkManager.getInstance(appContext) }.onFailure {
            val configuration = Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
            logHarnessInfo("Initialising test WorkManager for screenshot harness")
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun openDocumentInViewer() {
        logHarnessInfo("Opening thousand-page document in viewer: $documentUri")
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
                val error = IllegalStateException("Failed to load document for screenshots: $message")
                logHarnessError(error.message ?: "Failed to load document for screenshots", error)
                throw error
            }
            if (documentReady) {
                logHarnessInfo(
                    "Thousand-page document finished loading with pageCount=${snapshot?.pageCount}"
                )
                device.waitForIdle()
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastLog >= 5_000L) {
                val state = snapshot
                logHarnessInfo(
                    "Waiting for document to load; status=${state?.documentStatus?.javaClass?.simpleName} " +
                        "pageCount=${state?.pageCount} renderProgress=${state?.renderProgress}"
                )
                lastLog = now
            }
            Thread.sleep(250)
        }

        val error = IllegalStateException("Timed out waiting for document to finish loading for screenshots")
        logHarnessError(error.message ?: "Timed out waiting for document load", error)
        throw error
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

    private fun logHarnessInfo(message: String) {
        logHarness(Log.INFO, message, null)
    }

    private fun logHarnessWarn(message: String, error: Throwable? = null) {
        logHarness(Log.WARN, message, error)
    }

    private fun logHarnessError(message: String, error: Throwable) {
        logHarness(Log.ERROR, message, error)
    }

    private fun logHarness(level: Int, message: String, error: Throwable?) {
        when (level) {
            Log.ERROR -> if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
            Log.WARN -> if (error != null) Log.w(TAG, message, error) else Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }

        if (error != null) {
            println("$TAG: $message\n${Log.getStackTraceString(error)}")
        } else {
            println("$TAG: $message")
        }
    }

    class HarnessTestWatcher(
        private val onEvent: (String) -> Unit,
        private val onFailure: (String, Throwable) -> Unit,
    ) : TestWatcher() {
        override fun starting(description: Description) {
            onEvent("Starting ${description.displayName}")
        }

        override fun succeeded(description: Description) {
            onEvent("Completed ${description.displayName}")
        }

        override fun failed(e: Throwable, description: Description) {
            onFailure("Failed ${description.displayName}", e)
        }

        override fun finished(description: Description) {
            onEvent("Finished ${description.displayName}")
        }
    }
}
