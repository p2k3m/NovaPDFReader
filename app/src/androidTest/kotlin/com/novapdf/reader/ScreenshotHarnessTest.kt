package com.novapdf.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.novapdf.reader.logging.NovaLog
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.novapdf.reader.CacheFileNames
import com.novapdf.reader.util.sanitizeCacheFileName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.collections.buildList
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.text.Charsets
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class ScreenshotHarnessTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(ReaderActivity::class.java)

    @get:Rule(order = 2)
    val harnessLoggerRule = HarnessTestWatcher(
        onEvent = { message -> logHarnessInfo(message) },
        onFailure = { message, error -> logHarnessError(message, error) }
    )

    @Inject
    lateinit var testDocumentFixtures: TestDocumentFixtures

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var documentUri: Uri
    private lateinit var handshakeCacheDirs: List<File>
    private lateinit var handshakePackageName: String
    private var harnessEnabled: Boolean = false
    private var programmaticScreenshotsEnabled: Boolean = false
    private var metricsRecorder: PerformanceMetricsRecorder? = null

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
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
        programmaticScreenshotsEnabled = shouldCaptureProgrammaticScreenshots()
        logHarnessInfo("Programmatic screenshots enabled=$programmaticScreenshotsEnabled")
        metricsRecorder = PerformanceMetricsRecorder(appContext)
        ensureWorkManagerInitialized(appContext)
        logHarnessInfo("Installing thousand-page stress document for screenshot harness")
        documentUri = testDocumentFixtures.installThousandPageDocument(appContext)
        logHarnessInfo("Thousand-page document installed at $documentUri")
        cancelWorkManagerJobs()
    }

    private fun buildScreenshotReadyPayload(target: ScreenshotTarget?): String {
        return try {
            JSONObject().apply {
                put("status", "ready")
                if (target != null) {
                    put("documentId", target.documentId)
                    put("sanitizedDocumentId", target.sanitizedDocumentId)
                    put("pageIndex", target.pageIndex)
                    put("pageNumber", target.pageNumber)
                    put("pageLabel", target.pageLabel)
                    put("pageCount", target.pageCount)
                }
            }.toString()
        } catch (error: Exception) {
            logHarnessWarn("Failed to encode screenshot ready payload; falling back to legacy format", error)
            "ready"
        }
    }

    private fun resolveScreenshotTarget(): ScreenshotTarget? {
        if (!::documentUri.isInitialized) {
            return null
        }

        var snapshot: PdfViewerUiState? = null
        activityRule.scenario.onActivity { activity ->
            snapshot = activity.currentDocumentStateForTest()
        }
        val state = snapshot ?: return null
        if (state.pageCount <= 0) {
            return null
        }

        val resolvedDocumentId = state.documentId ?: documentUri.toString()
        val sanitizedDocumentId = sanitizeCacheFileName(
            raw = resolvedDocumentId,
            fallback = documentUri.toString(),
            label = "screenshot document id",
        ).ifEmpty { "document" }
        val safePageIndex = when {
            state.currentPage in 0 until state.pageCount -> state.currentPage
            else -> 0
        }
        val pageNumber = (safePageIndex + 1).coerceIn(1, max(1, state.pageCount))
        val paddedLabel = String.format(Locale.US, "%04d", pageNumber)
        return ScreenshotTarget(
            documentId = resolvedDocumentId,
            sanitizedDocumentId = sanitizedDocumentId,
            pageIndex = safePageIndex,
            pageNumber = pageNumber,
            pageLabel = paddedLabel,
            pageCount = state.pageCount,
        )
    }

    @After
    fun tearDown() = runBlocking {
        if (!harnessEnabled || !::handshakeCacheDirs.isInitialized) {
            return@runBlocking
        }
        metricsRecorder?.finish()?.let { report ->
            publishPerformanceMetrics(report)
        }
        metricsRecorder = null
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
            File(directory, CacheFileNames.SCREENSHOT_READY_FLAG)
        }
        val doneFlags = handshakeCacheDirs.map { directory ->
            File(directory, CacheFileNames.SCREENSHOT_DONE_FLAG)
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

        if (programmaticScreenshotsEnabled) {
            captureProgrammaticScreenshot(doneFlags)
            return
        }

        val screenshotTarget = resolveScreenshotTarget()
        if (screenshotTarget != null) {
            logHarnessInfo(
                "Prepared screenshot target for documentId=${screenshotTarget.documentId} " +
                    "pageIndex=${screenshotTarget.pageIndex} pageNumber=${screenshotTarget.pageNumber}"
            )
        } else {
            logHarnessWarn("Unable to resolve screenshot metadata; ready flag payload will omit document details")
        }
        val readyPayload = buildScreenshotReadyPayload(screenshotTarget)
        readyFlags.forEach { flag ->
            logHarnessInfo(
                "Writing screenshot ready flag to ${flag.absolutePath} with payload $readyPayload"
            )
        }
        withContext(Dispatchers.IO) {
            readyFlags.forEach { flag ->
                runCatching { writeHandshakeFlag(flag, readyPayload) }
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
        val completedFlag = awaitScreenshotCompletionFlag(readyFlags, doneFlags, start)

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

    private suspend fun awaitScreenshotCompletionFlag(
        readyFlags: List<File>,
        doneFlags: List<File>,
        startTimeMillis: Long,
    ): File {
        doneFlags.firstOrNull(::flagExists)?.let { return it }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val directories = doneFlags.mapNotNull(File::getParentFile).distinct()

        return suspendCancellableCoroutine { continuation ->
            val handlerThread = HandlerThread("ScreenshotFlagObserver").apply { start() }
            val handler = Handler(handlerThread.looper)
            val finished = AtomicBoolean(false)
            var observer: ContentObserver? = null
            var pollRunnable: Runnable? = null
            var backoffMillis = FLAG_OBSERVER_INITIAL_BACKOFF_MS
            var lastLog = startTimeMillis

            fun cleanup() {
                pollRunnable?.let { handler.removeCallbacks(it) }
                observer?.let { runCatching { resolver.unregisterContentObserver(it) } }
                handlerThread.quitSafely()
            }

            fun finishWithSuccess(flag: File) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(flag)
                }
            }

            fun finishWithError(error: Throwable) {
                if (!finished.compareAndSet(false, true)) return
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            fun maybeCheck(trigger: String) {
                if (!continuation.isActive || finished.get()) {
                    cleanup()
                    return
                }
                val now = System.currentTimeMillis()
                val elapsed = now - startTimeMillis
                var activityRunning = true
                instrumentation.runOnMainSync {
                    activityRunning = activityRule.scenario.state.isAtLeast(Lifecycle.State.STARTED)
                }
                if (!activityRunning) {
                    val error = IllegalStateException(
                        "ReaderActivity unexpectedly stopped while waiting for screenshots"
                    )
                    logHarnessError(error.message ?: "ReaderActivity stopped unexpectedly", error)
                    finishWithError(error)
                    return
                }
                if (elapsed > SCREENSHOT_COMPLETION_TIMEOUT_MS) {
                    val error = IllegalStateException("Timed out waiting for host screenshot completion signal")
                    logHarnessError(error.message ?: "Timed out waiting for screenshot completion", error)
                    finishWithError(error)
                    return
                }
                val completedFlag = doneFlags.firstOrNull(::flagExists)
                metricsRecorder?.sample()
                if (completedFlag != null) {
                    finishWithSuccess(completedFlag)
                    return
                }
                if (now - lastLog >= TimeUnit.SECONDS.toMillis(15)) {
                    logHarnessInfo(
                        "Screenshot harness still waiting; ready flags present=${readyFlags.count(::flagExists)} " +
                            "done flags present=${doneFlags.count(::flagExists)}"
                    )
                    lastLog = now
                }
                backoffMillis = when (trigger) {
                    "observer" -> FLAG_OBSERVER_INITIAL_BACKOFF_MS
                    "initial" -> backoffMillis
                    else -> min(backoffMillis * 2, FLAG_OBSERVER_MAX_BACKOFF_MS)
                }
                if (!finished.get() && continuation.isActive) {
                    pollRunnable?.let { handler.removeCallbacks(it) }
                    val runnable = Runnable { maybeCheck("poll") }
                    pollRunnable = runnable
                    handler.postDelayed(runnable, backoffMillis)
                }
            }

            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    maybeCheck("observer")
                }
            }

            val observerInstance = checkNotNull(observer) {
                "Screenshot flag observer should be initialised before registering directory observers."
            }
            directories.forEach { directory ->
                runCatching {
                    resolver.registerContentObserver(Uri.fromFile(directory), true, observerInstance)
                }.onFailure { error ->
                    logHarnessWarn(
                        "Unable to register content observer for ${directory.absolutePath}; falling back to polling",
                        error
                    )
                }
            }

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    cleanup()
                }
            }

            maybeCheck("initial")
        }
    }

    private fun publishPerformanceMetrics(report: PerformanceMetricsReport) {
        val csv = buildString {
            appendLine("metric,value,unit,details")
            appendLine("time_to_first_page,${report.timeToFirstPageMs},ms,")
            val peakBytes = report.peakTotalPssKb.toLong() * 1024L
            appendLine("peak_memory,${peakBytes},bytes,source=totalPss")
            appendLine(
                "dropped_frames,${report.droppedFrames},frames,total_frames=${report.totalFrames}"
            )
        }
        val directories = if (::handshakeCacheDirs.isInitialized && handshakeCacheDirs.isNotEmpty()) {
            handshakeCacheDirs
        } else {
            listOfNotNull(if (::appContext.isInitialized) appContext.cacheDir else null)
        }
        directories.forEach { directory ->
            val outputFile = File(directory, CacheFileNames.PERFORMANCE_METRICS_FILE)
            runCatching { writeHandshakeFlag(outputFile, csv) }
                .onSuccess {
                    logHarnessInfo(
                        "Wrote performance metrics to ${outputFile.absolutePath} " +
                            "(timeToFirstPage=${report.timeToFirstPageMs}ms peakPss=${report.peakTotalPssKb}KiB " +
                            "droppedFrames=${report.droppedFrames}/${report.totalFrames})"
                    )
                }
                .onFailure { error ->
                    logHarnessWarn(
                        "Unable to write performance metrics to ${outputFile.absolutePath}",
                        error
                    )
                }
        }
    }

    private fun cleanupFlags() {
        if (!::handshakeCacheDirs.isInitialized) {
            return
        }
        handshakeCacheDirs.forEach { directory ->
            logHarnessInfo("Cleaning up screenshot harness flags in ${directory.absolutePath}")
            deleteHandshakeFlag(
                File(directory, CacheFileNames.SCREENSHOT_READY_FLAG),
                failOnError = false
            )
            deleteHandshakeFlag(
                File(directory, CacheFileNames.SCREENSHOT_DONE_FLAG),
                failOnError = false
            )
        }
    }

    private fun writeHandshakeFlag(flag: File, contents: String) {
        val parent = flag.parentFile
        if (parent != null && !ensureHandshakeDirectory(parent)) {
            val error = IOException(
                "Unable to create directory for handshake flag at ${parent.absolutePath}"
            )
            logHarnessError(error.message ?: "Unable to create handshake directory", error)
            throw error
        }

        try {
            flag.writeText(contents, Charsets.UTF_8)
            return
        } catch (error: SecurityException) {
            if (writeFlagWithRunAs(flag, contents)) {
                return
            }
            val wrapped = IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
            logHarnessError(wrapped.message ?: "Failed to create handshake flag", wrapped)
            throw wrapped
        } catch (error: IOException) {
            if (writeFlagWithRunAs(flag, contents)) {
                return
            }
            val wrapped = IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
            logHarnessError(wrapped.message ?: "Failed to create handshake flag", wrapped)
            throw wrapped
        }
    }

    private fun deleteHandshakeFlag(flag: File, failOnError: Boolean = true) {
        if (!flag.exists()) {
            if (!existsWithRunAs(flag)) {
                return
            }
        }

        if (!deleteFlag(flag)) {
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
        if (!flag.exists()) {
            if (!existsWithRunAs(flag)) {
                return true
            }
        }

        if (flag.delete()) {
            return true
        }
        return deleteFlagWithRunAs(flag)
    }

    private fun resolveHandshakeCacheDirs(testPackageName: String): List<File> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val candidateDirectories = buildList {
            runCatching { findTestPackageCacheDir(testPackageName) }
                .onFailure { error ->
                    logHarnessWarn(
                        "Falling back to derived screenshot handshake cache directory",
                        error
                    )
                }
                .getOrNull()
                ?.let(::add)

            deriveTestPackageCacheDir(instrumentation, testPackageName)?.let(::add)

            addAll(cacheCandidatesForContext(instrumentation.context))
        }

        val resolvedDirectories = candidateDirectories
            .filter(::prepareCacheDirectory)
            .mapNotNull { directory ->
                runCatching { directory.canonicalFile }.getOrElse { directory }
            }
            .distinctBy { it.absolutePath }

        if (resolvedDirectories.isEmpty()) {
            logHarnessWarn(
                "Primary cache directory probes failed for screenshot handshake; attempting instrumentation cache fallback"
            )

            val fallbackDirectories = buildList {
                add(instrumentation.context.cacheDir)
                add(instrumentation.context.applicationContext?.cacheDir)
                add(instrumentation.targetContext.cacheDir)
                add(instrumentation.targetContext.applicationContext?.cacheDir)
            }
                .filterNotNull()
                .filter(::prepareCacheDirectory)
                .mapNotNull { directory ->
                    runCatching { directory.canonicalFile }.getOrElse { directory }
                }
                .distinctBy { it.absolutePath }

            if (fallbackDirectories.isEmpty()) {
                throw IllegalStateException(
                    "Instrumentation cache directory unavailable for screenshot handshake"
                )
            }

            logHarnessInfo(
                "Resolved screenshot handshake cache directories ${fallbackDirectories.joinToString { it.absolutePath }} (fallback)"
            )
            return fallbackDirectories
        }

        logHarnessInfo(
            "Resolved screenshot handshake cache directories ${resolvedDirectories.joinToString { it.absolutePath }}"
        )

        return resolvedDirectories
    }

    private fun cacheCandidatesForContext(context: Context): List<File> {
        val candidates = mutableListOf<File>()

        fun tryAdd(label: String, provider: () -> File?) {
            val directory = try {
                provider()
            } catch (error: Exception) {
                logHarnessWarn(
                    "Skipping $label for screenshot harness cache directory; unavailable on this device",
                    error
                )
                null
            }
            if (directory != null) {
                candidates += directory
            }
        }

        tryAdd("cacheDir") { context.cacheDir }
        tryAdd("codeCacheDir") { context.codeCacheDir }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tryAdd("credentialProtectedStorage") {
                context.credentialProtectedStorageContext().cacheDir
            }
        }

        return candidates
    }

    private fun prepareCacheDirectory(directory: File?): Boolean {
        if (directory == null) {
            return false
        }

        return if (ensureHandshakeDirectory(directory)) {
            true
        } else {
            logHarnessWarn(
                "Unable to prepare screenshot handshake cache directory at ${directory.absolutePath}"
            )
            false
        }
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

    private fun deriveTestPackageCacheDir(
        instrumentation: android.app.Instrumentation,
        testPackageName: String
    ): File? {
        val targetCacheDir = runCatching { instrumentation.targetContext.cacheDir }.getOrNull()
            ?: return null
        val userDirectory = targetCacheDir.parentFile?.parentFile ?: return null
        return File(userDirectory, "$testPackageName/cache")
    }

    private fun resolveTestPackageName(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()

        val explicit = arguments.getString("testPackageName")?.takeIf { it.isNotBlank() }
        if (!explicit.isNullOrEmpty()) {
            return explicit
        }

        val targetInstrumentation = arguments.getString("targetInstrumentation")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
        if (!targetInstrumentation.isNullOrEmpty()) {
            return targetInstrumentation
        }

        val placeholder = arguments.getString("novapdfTestAppId")?.takeIf { it.isNotBlank() }
        if (!placeholder.isNullOrEmpty()) {
            return placeholder
        }

        val instrumentationPackage = instrumentation.context.packageName
        if (instrumentationPackage.endsWith(".test")) {
            return instrumentationPackage
        }

        val targetPackage = instrumentation.targetContext.packageName
        val derived = if (instrumentationPackage.isNotBlank() &&
            instrumentationPackage != targetPackage
        ) {
            instrumentationPackage
        } else {
            "$targetPackage.test"
        }

        return derived
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
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
            logHarnessInfo("Initialising test WorkManager for screenshot harness")
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
    }

    private fun shouldRunHarness(): Boolean {
        val argument = InstrumentationRegistry.getArguments().getString(HARNESS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun shouldCaptureProgrammaticScreenshots(): Boolean {
        val argument = InstrumentationRegistry.getArguments()
            .getString(PROGRAMMATIC_SCREENSHOTS_ARGUMENT)
        return argument?.lowercase(Locale.US) == "true"
    }

    private fun openDocumentInViewer() {
        logHarnessInfo("Opening thousand-page document in viewer: $documentUri")
        metricsRecorder?.start(activityRule.scenario)
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
            metricsRecorder?.sample()

            errorMessage?.let { message ->
                val snapshotState = snapshot
                if (snapshotState != null) {
                    logHarnessWarn(
                        "Document load reported error with state=${snapshotState.documentStatus.javaClass.simpleName} " +
                            "pageCount=${snapshotState.pageCount} renderProgress=${snapshotState.renderProgress}"
                    )
                }
                val error = IllegalStateException("Failed to load document for screenshots: $message")
                logHarnessError(error.message ?: "Failed to load document for screenshots", error)
                throw error
            }
            if (documentReady) {
                metricsRecorder?.markFirstPageRendered()
                metricsRecorder?.sample()
                logHarnessInfo(
                    "Thousand-page document finished loading with pageCount=${snapshot?.pageCount}"
                )
                device.waitForIdle(DEVICE_IDLE_TIMEOUT_MS)
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
            metricsRecorder?.sample()
            Thread.sleep(250)
        }

        val error = IllegalStateException("Timed out waiting for document to finish loading for screenshots")
        logHarnessError(error.message ?: "Timed out waiting for document load", error)
        throw error
    }

    private fun flagExists(flag: File): Boolean {
        return try {
            if (flag.exists()) {
                true
            } else {
                existsWithRunAs(flag)
            }
        } catch (_: SecurityException) {
            existsWithRunAs(flag)
        }
    }

    private fun ensureHandshakeDirectory(directory: File): Boolean {
        if (directory.safeExists()) {
            return true
        }

        val created = try {
            directory.mkdirs()
        } catch (_: SecurityException) {
            false
        }
        if (created || directory.safeExists()) {
            return true
        }

        if (canUseRunAs(directory) && createDirectoryWithRunAs(directory)) {
            return true
        }

        return directory.safeExists()
    }

    private fun File.safeExists(): Boolean = runCatching { exists() }.getOrElse { false }

    private fun canUseRunAs(file: File): Boolean {
        if (!::handshakePackageName.isInitialized) {
            return false
        }
        val path = file.absolutePath
        val packageName = handshakePackageName
        return path.contains("/$packageName/")
    }

    private fun createDirectoryWithRunAs(directory: File): Boolean {
        if (!canUseRunAs(directory)) {
            return false
        }
        val command = "mkdir -p \"${directory.absolutePath}\""
        runAsShellCommand(command, logError = false)
        return directory.safeExists() || directoryExistsWithRunAs(directory)
    }

    private fun writeFlagWithRunAs(flag: File, contents: String): Boolean {
        if (!canUseRunAs(flag)) {
            return false
        }
        flag.parentFile?.let { parent ->
            if (!createDirectoryWithRunAs(parent)) {
                return false
            }
        }
        val escaped = sanitizeForSingleQuotes(contents)
        val command = "printf '%s' '$escaped' > \"${flag.absolutePath}\""
        runAsShellCommand(command, logError = false)
        return existsWithRunAs(flag)
    }

    private fun deleteFlag(flag: File): Boolean {
        return try {
            if (flag.delete()) {
                true
            } else {
                deleteFlagWithRunAs(flag)
            }
        } catch (_: SecurityException) {
            deleteFlagWithRunAs(flag)
        }
    }

    private fun deleteFlagWithRunAs(flag: File): Boolean {
        if (!canUseRunAs(flag)) {
            return false
        }
        val command = "rm -f \"${flag.absolutePath}\""
        runAsShellCommand(command, logError = false)
        return !existsWithRunAs(flag)
    }

    private fun existsWithRunAs(flag: File): Boolean {
        if (!canUseRunAs(flag)) {
            return false
        }
        val command = "if [ -f \"${flag.absolutePath}\" ]; then echo EXISTS; fi"
        val output = runAsShellCommand(command, logError = false) ?: return false
        return output.lineSequence().any { it.trim() == "EXISTS" }
    }

    private suspend fun captureProgrammaticScreenshot(doneFlags: List<File>) {
        if (!::appContext.isInitialized) {
            logHarnessWarn("Programmatic screenshot capture skipped; app context not initialised")
            return
        }

        logHarnessInfo("Capturing programmatic screenshot via UiDevice")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val adoptedPermissions = mutableListOf<String>()
        var screenshot: Bitmap? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                adoptedPermissions += Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                adoptedPermissions += CAPTURE_VIDEO_OUTPUT_PERMISSION
            }
            if (adoptedPermissions.isNotEmpty()) {
                automation.adoptShellPermissionIdentity(*adoptedPermissions.toTypedArray())
                logHarnessInfo(
                    "Adopted shell permissions ${adoptedPermissions.joinToString()} for programmatic screenshot"
                )
            }

            @Suppress("DEPRECATION")
            val capturedScreenshot = automation.takeScreenshot()
            screenshot = capturedScreenshot
            if (capturedScreenshot == null) {
                logHarnessWarn("Programmatic screenshot capture returned null bitmap")
                return
            }

            val screenshotDir = File(
                appContext.cacheDir,
                CacheFileNames.INSTRUMENTATION_SCREENSHOT_DIRECTORY
            ).apply { mkdirs() }
            val screenshotFile = File(screenshotDir, CacheFileNames.PROGRAMMATIC_SCREENSHOT_FILE)
            withContext(Dispatchers.IO) {
                runCatching {
                    FileOutputStream(screenshotFile).use { stream ->
                        if (!capturedScreenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw IOException("Failed to compress programmatic screenshot")
                        }
                    }
                }.onFailure { error ->
                    logHarnessError(
                        error.message ?: "Failed to persist programmatic screenshot",
                        error
                    )
                    screenshotFile.delete()
                    throw error
                }
            }
            logHarnessInfo("Programmatic screenshot saved to ${screenshotFile.absolutePath}")

            withContext(Dispatchers.IO) {
                doneFlags.forEach { flag ->
                    runCatching { writeHandshakeFlag(flag, screenshotFile.absolutePath) }
                        .onFailure { error ->
                            logHarnessWarn(
                                "Unable to publish programmatic screenshot completion flag at ${flag.absolutePath}",
                                error
                            )
                        }
                }
            }
        } catch (error: SecurityException) {
            logHarnessError(
                error.message ?: "Security exception during programmatic screenshot capture",
                error
            )
        } catch (error: IOException) {
            logHarnessError(
                error.message ?: "IO exception during programmatic screenshot capture",
                error
            )
        } catch (error: RuntimeException) {
            logHarnessError(
                error.message ?: "Unexpected error during programmatic screenshot capture",
                error
            )
        } finally {
            screenshot?.recycle()
            if (adoptedPermissions.isNotEmpty()) {
                automation.dropShellPermissionIdentity()
                logHarnessInfo("Dropped adopted shell permissions after programmatic screenshot")
            }
        }
    }

    private fun directoryExistsWithRunAs(directory: File): Boolean {
        if (!canUseRunAs(directory)) {
            return false
        }
        val command = "if [ -d \"${directory.absolutePath}\" ]; then echo DIR; fi"
        val output = runAsShellCommand(command, logError = false) ?: return false
        return output.lineSequence().any { it.trim() == "DIR" }
    }

    private fun runAsShellCommand(rawCommand: String, logError: Boolean = true): String? {
        if (!::handshakePackageName.isInitialized) {
            return null
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sanitized = sanitizeForSingleQuotes(rawCommand)
        val fullCommand = "run-as $handshakePackageName sh -c '$sanitized'"
        return try {
            instrumentation.uiAutomation.executeShellCommand(fullCommand).use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    stream.bufferedReader().use { it.readText() }
                }
            }
        } catch (error: Throwable) {
            if (logError) {
                logHarnessWarn("run-as command failed: $rawCommand", error)
            }
            null
        }
    }

    private fun sanitizeForSingleQuotes(value: String): String {
        return value.replace("'", "'\"'\"'")
    }

    private data class ScreenshotTarget(
        val documentId: String,
        val sanitizedDocumentId: String,
        val pageIndex: Int,
        val pageNumber: Int,
        val pageLabel: String,
        val pageCount: Int,
    )

    private companion object {
        private const val HARNESS_ARGUMENT = "runScreenshotHarness"
        private const val PROGRAMMATIC_SCREENSHOTS_ARGUMENT = "captureProgrammaticScreenshots"
        private const val CAPTURE_VIDEO_OUTPUT_PERMISSION = "android.permission.CAPTURE_VIDEO_OUTPUT"
        private val SCREENSHOT_COMPLETION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5)
        private const val FLAG_OBSERVER_INITIAL_BACKOFF_MS = 250L
        private const val FLAG_OBSERVER_MAX_BACKOFF_MS = 5_000L
        // Opening a thousand-page stress document can take a while on CI devices, so give the
        // viewer ample time to finish rendering before failing the harness run.
        private const val DOCUMENT_OPEN_TIMEOUT = 180_000L
        private const val WORK_MANAGER_CANCEL_TIMEOUT_SECONDS = 15L
        private const val DEVICE_IDLE_TIMEOUT_MS = 10_000L
        private const val TAG = "ScreenshotHarness"
    }

    private fun logHarnessInfo(message: String) {
        NovaLog.i(TAG, message)
        println("$TAG: $message")
    }

    private fun logHarnessWarn(message: String, error: Throwable? = null) {
        if (error != null) {
            NovaLog.w(tag = TAG, message = message, throwable = error)
            println("$TAG: $message\n${android.util.Log.getStackTraceString(error)}")
        } else {
            NovaLog.w(tag = TAG, message = message)
            println("$TAG: $message")
        }
    }

    private fun logHarnessError(message: String, error: Throwable) {
        NovaLog.e(tag = TAG, message = message, throwable = error)
        println("$TAG: $message\n${android.util.Log.getStackTraceString(error)}")
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
