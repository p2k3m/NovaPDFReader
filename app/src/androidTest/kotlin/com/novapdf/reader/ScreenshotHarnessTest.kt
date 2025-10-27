package com.novapdf.reader

import android.Manifest
import android.app.Instrumentation
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.novapdf.reader.CacheFileNames
import com.novapdf.reader.logging.LogField
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.util.sanitizeCacheFileName
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.collections.buildList
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
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

    @get:Rule(order = 2)
    val harnessLoggerRule = HarnessTestWatcher(
        onEvent = { message -> logHarnessInfo(message) },
        onFailure = { message, error ->
            logHarnessError(message, error)
            publishHarnessFailureFlag(message, error)
        },
        onLifecycleChange = { event, description ->
            when (event) {
                HarnessTestWatcher.LifecycleEvent.STARTED -> {
                    currentTestDescription = description.displayName
                    lastProgressStep = null
                    lastLoggedUiState = null
                    uiReadyUnderLoadAccepted = false
                }
                HarnessTestWatcher.LifecycleEvent.FINISHED -> {
                    currentTestDescription = null
                    lastProgressStep = null
                    lastLoggedUiState = null
                    uiReadyUnderLoadAccepted = false
                }
            }
        }
    )

    @get:Rule(order = 3)
    val resourceMonitorRule = DeviceResourceMonitorRule(
        contextProvider = { runCatching { ApplicationProvider.getApplicationContext<Context>() }.getOrNull() },
        logger = { message -> logHarnessInfo(message) },
        onResourceExhausted = { reason ->
            logHarnessWarn("Resource exhaustion detected: $reason")
            publishHarnessFailureFlag("resource_exhausted", IllegalStateException(reason))
        },
    )

    @Inject
    lateinit var testDocumentFixtures: TestDocumentFixtures

    @Inject
    lateinit var harnessOverrides: HarnessOverrideRegistry

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private lateinit var activityScenario: ActivityScenario<ReaderActivity>
    private lateinit var documentUri: Uri
    private var documentSourceOverride: DocumentSource? = null
    private lateinit var handshakeCacheDirs: List<File>
    private lateinit var handshakePackageName: String
    private lateinit var deviceTimeouts: DeviceAdaptiveTimeouts
    private var harnessEnabled: Boolean = false
    private var programmaticScreenshotsEnabled: Boolean = false
    private var metricsRecorder: PerformanceMetricsRecorder? = null
    private var failureFlagPublished: Boolean = false
    private var crashFlagPublished: Boolean = false
    private var screenshotCompletionTimeoutMs: Long = DEFAULT_SCREENSHOT_COMPLETION_TIMEOUT_MS
    private var currentTestDescription: String? = null
    private var lastProgressStep: HarnessProgressStep? = null
    private var lastLoggedUiState: PdfViewerUiState? = null
    private var uiReadyUnderLoadAccepted: Boolean = false
    private val staticDeviceFields: List<LogField> by lazy {
        listOf(
            field("deviceApi", Build.VERSION.SDK_INT),
            field("deviceModel", Build.MODEL ?: "unknown"),
            field("deviceManufacturer", Build.MANUFACTURER ?: "unknown"),
            field("deviceProduct", Build.PRODUCT ?: "unknown"),
        )
    }

    @Before
    fun setUp() {
        runBlocking {
            lastLoggedUiState = null
            lastProgressStep = null
            try {
                hiltRule.inject()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            HarnessReadiness.emit { readinessMarker ->
                println(readinessMarker)
                logHarnessInfo("Harness readiness marker emitted: $readinessMarker")
            }
            HarnessTestPoints.emit(HarnessTestPoint.PRE_INITIALIZATION)
            val harnessRequested = shouldRunHarness()
            logHarnessInfo("Screenshot harness requested=$harnessRequested")
            assumeTrue("Screenshot harness disabled", harnessRequested)

            appContext = ApplicationProvider.getApplicationContext()
            (appContext as? NovaPdfApp)?.ensureStrictModeHarnessOverride()
            activityScenario = ActivityScenario.launch(ReaderActivity::class.java)
            performSlowSystemPreflight()
            device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            handshakePackageName = resolveTestPackageName()
            logHarnessInfo("Resolved screenshot harness package name: $handshakePackageName")
            screenshotCompletionTimeoutMs = resolveScreenshotCompletionTimeoutMillis(appContext)
            logHarnessInfo(
                "Adaptive screenshot completion timeout=${screenshotCompletionTimeoutMs / 1000} seconds",
            )
            handshakeCacheDirs = resolveHandshakeCacheDirs(handshakePackageName)
            logHarnessInfo(
                "Using handshake cache directories ${handshakeCacheDirs.joinToString { it.absolutePath }} " +
                    "for package $handshakePackageName"
            )
            withContext(Dispatchers.IO) { cleanupFlags(deleteStatusArtifacts = true) }
            HarnessTestPoints.emit(
                HarnessTestPoint.CACHE_READY,
                "directories=${handshakeCacheDirs.joinToString { it.absolutePath }}"
            )
            recordHarnessProgress(
                HarnessProgressStep.TEST_INITIALISED,
                "handshakeDirectories=${handshakeCacheDirs.size}"
            )
            harnessEnabled = true
            programmaticScreenshotsEnabled = shouldCaptureProgrammaticScreenshots()
            logHarnessInfo("Programmatic screenshots enabled=$programmaticScreenshotsEnabled")
            metricsRecorder = PerformanceMetricsRecorder(appContext)
            ensureWorkManagerInitialized(appContext)
            documentSourceOverride = null
            val harnessDocument = harnessOverrides.prepareDocument(appContext)
            if (harnessDocument != null) {
                when (harnessDocument) {
                    is HarnessDocument.Local -> {
                        documentUri = harnessDocument.uri
                        logHarnessInfo(
                            "Using override local document for screenshot harness: $documentUri",
                        )
                    }
                    is HarnessDocument.Remote -> {
                        documentSourceOverride = harnessDocument.source
                        documentUri = Uri.parse(harnessDocument.source.id)
                        logHarnessInfo(
                            "Using override document source for screenshot harness: " +
                                "${harnessDocument.source.kind}:${harnessDocument.source.id}",
                        )
                    }
                }
            } else {
                logHarnessInfo("Installing thousand-page stress document for screenshot harness")
                documentUri = testDocumentFixtures.installThousandPageDocument(appContext)
                logHarnessInfo("Thousand-page document installed at $documentUri")
            }
            cancelWorkManagerJobs()
        } catch (error: Throwable) {
            if (error is AssumptionViolatedException || error is CancellationException) {
                throw error
            }
            val message = error.message ?: "Failed to set up screenshot harness"
            logHarnessError(message, error)
            publishHarnessFailureFlag("setup_failed", error)
            throw error
        }
        }
    }

    private fun performSlowSystemPreflight() {
        val sample = capturePreflightCpuSample()
        if (sample == null) {
            logHarnessInfo("Preflight CPU sample unavailable; continuing with screenshot harness setup")
            return
        }

        val totalText = String.format(Locale.US, "%.1f%%", sample.totalPercent)
        val processText = sample.processPercent?.let { percent ->
            String.format(Locale.US, "%.1f%%", percent)
        } ?: "n/a"
        val otherText = sample.otherPercent?.let { percent ->
            String.format(Locale.US, "%.1f%%", percent)
        } ?: "n/a"

        logHarnessInfo(
            "Preflight CPU sample: total=$totalText, process=$processText, other=$otherText",
        )

        if (!sample.isCpuElevated()) {
            return
        }

        logHarnessWarn(
            "Preflight CPU sample exceeded thresholds; capturing confirmation sample before skipping",
        )

        val confirmation = capturePreflightCpuSample()
        if (confirmation == null || !confirmation.isCpuElevated()) {
            val confirmationDetail = confirmation?.let { confirmSample ->
                buildPreflightDetail(confirmSample)
            } ?: "confirmation_sample=unavailable"
            logHarnessInfo(
                "System CPU load normalized after additional sampling; continuing with screenshot harness setup ($confirmationDetail)",
            )
            return
        }

        val detail = buildString {
            append(buildPreflightDetail(sample))
            append(' ')
            append("confirmation=")
            append(buildPreflightDetail(confirmation))
        }
        logHarnessWarn("Skipping screenshot harness due to elevated system CPU load. $detail")
        publishHarnessSkipFlag(
            reason = "preflight_system_cpu",
            detail = detail,
            blacklistRunner = true,
        )
        assumeTrue("System CPU load too high for screenshot harness preflight", false)
    }

    private fun capturePreflightCpuSample(): CpuPreflightSample? {
        val first = readPreflightSnapshot() ?: return null
        SystemClock.sleep(PREFLIGHT_CPU_SAMPLE_INTERVAL_MS)
        val second = readPreflightSnapshot() ?: return null

        val totalDelta = second.total - first.total
        val idleDelta = second.idle - first.idle
        if (totalDelta <= 0 || idleDelta < 0) {
            return null
        }

        val busy = totalDelta - idleDelta
        if (busy <= 0) {
            val processPercent = if (first.process != null) 0f else null
            return CpuPreflightSample(
                totalPercent = 0f,
                processPercent = processPercent,
                otherPercent = processPercent?.let { 0f },
            )
        }

        val totalPercent = (busy.toDouble() / totalDelta.toDouble() * 100.0).toFloat()
        val processPercent = if (first.process != null && second.process != null) {
            val processDelta = second.process - first.process
            if (processDelta <= 0) {
                0f
            } else {
                val rawPercent = (processDelta.toDouble() / totalDelta.toDouble() * 100.0).toFloat()
                rawPercent.coerceAtMost(totalPercent).coerceAtLeast(0f)
            }
        } else {
            null
        }
        val otherPercent = processPercent?.let { percent ->
            (totalPercent - percent).coerceAtLeast(0f)
        }
        return CpuPreflightSample(
            totalPercent = totalPercent,
            processPercent = processPercent,
            otherPercent = otherPercent,
        )
    }

    private fun readPreflightSnapshot(): CpuPreflightSnapshot? {
        val times = readAggregateCpuTimes() ?: return null
        val process = readProcessCpuJiffies()
        return CpuPreflightSnapshot(
            idle = times.idle,
            total = times.total,
            process = process,
        )
    }

    private fun readAggregateCpuTimes(): AggregateCpuTimes? {
        return runCatching {
            File("/proc/stat").useLines { sequence ->
                val line = sequence.firstOrNull() ?: return@useLines null
                if (!line.startsWith("cpu")) {
                    return@useLines null
                }
                val tokens = line.split(Regex("\\s+")).drop(1).filter { it.isNotBlank() }
                if (tokens.size < 4) {
                    return@useLines null
                }
                val values = tokens.mapNotNull { it.toLongOrNull() }
                if (values.size < 4) {
                    return@useLines null
                }
                val idle = values[3] + values.getOrElse(4) { 0L }
                val total = values.fold(0L) { acc, value -> acc + max(value, 0L) }
                AggregateCpuTimes(idle = idle, total = total)
            }
        }.getOrNull()
    }

    private fun readProcessCpuJiffies(): Long? {
        return runCatching {
            val statContents = File("/proc/self/stat").readText()
            val closing = statContents.lastIndexOf(')')
            if (closing < 0 || closing + 1 >= statContents.length) {
                return@runCatching null
            }
            val remainder = statContents.substring(closing + 1).trim()
            if (remainder.isEmpty()) {
                return@runCatching null
            }
            val tokens = remainder.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size < 13) {
                return@runCatching null
            }
            val utime = tokens[11].toLongOrNull() ?: return@runCatching null
            val stime = tokens[12].toLongOrNull() ?: return@runCatching null
            utime + stime
        }.getOrNull()
    }

    private data class CpuPreflightSample(
        val totalPercent: Float,
        val processPercent: Float?,
        val otherPercent: Float?,
    )

    private fun CpuPreflightSample.isCpuElevated(): Boolean {
        val otherPercent = otherPercent ?: return false
        return totalPercent >= PREFLIGHT_TOTAL_CPU_THRESHOLD_PERCENT &&
            otherPercent >= PREFLIGHT_OTHER_CPU_THRESHOLD_PERCENT
    }

    private fun buildPreflightDetail(sample: CpuPreflightSample): String {
        val totalText = String.format(Locale.US, "%.1f%%", sample.totalPercent)
        val processText = sample.processPercent?.let { percent ->
            String.format(Locale.US, "%.1f%%", percent)
        } ?: "n/a"
        val otherText = sample.otherPercent?.let { percent ->
            String.format(Locale.US, "%.1f%%", percent)
        } ?: "n/a"
        return String.format(
            Locale.US,
            "total=%s process=%s other=%s thresholds=%.0f/%.0f sampleIntervalMs=%d",
            totalText,
            processText,
            otherText,
            PREFLIGHT_TOTAL_CPU_THRESHOLD_PERCENT.toDouble(),
            PREFLIGHT_OTHER_CPU_THRESHOLD_PERCENT.toDouble(),
            PREFLIGHT_CPU_SAMPLE_INTERVAL_MS,
        )
    }

    private data class CpuPreflightSnapshot(
        val idle: Long,
        val total: Long,
        val process: Long?,
    )

    private data class AggregateCpuTimes(
        val idle: Long,
        val total: Long,
    )

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

    private fun resolveScreenshotTarget(state: PdfViewerUiState? = fetchViewerState()): ScreenshotTarget? {
        if (!::documentUri.isInitialized) {
            return null
        }

        val snapshot = state ?: return null
        if (!isUiInteractive(snapshot)) {
            return null
        }

        val resolvedDocumentId = snapshot.documentId ?: documentUri.toString()
        val sanitizedDocumentId = sanitizeCacheFileName(
            raw = resolvedDocumentId,
            fallback = documentUri.toString(),
            label = "screenshot document id",
        ).ifEmpty { "document" }
        val safePageIndex = when {
            snapshot.currentPage in 0 until snapshot.pageCount -> snapshot.currentPage
            else -> 0
        }
        val pageNumber = (safePageIndex + 1).coerceIn(1, max(1, snapshot.pageCount))
        val paddedLabel = String.format(Locale.US, "%04d", pageNumber)
        return ScreenshotTarget(
            documentId = resolvedDocumentId,
            sanitizedDocumentId = sanitizedDocumentId,
            pageIndex = safePageIndex,
            pageNumber = pageNumber,
            pageLabel = paddedLabel,
            pageCount = snapshot.pageCount,
        )
    }

    private fun viewerStateFlow(): Flow<PdfViewerUiState> = callbackFlow {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var registration: Closeable? = null
        activityScenario.onActivity { activity ->
            registration = activity.observeDocumentStateForTest { state ->
                logHarnessStateChange("activity_observer", state)
                trySend(state).isSuccess
            }
        }
        awaitClose {
            val closeable = registration ?: return@awaitClose
            instrumentation.runOnMainSync {
                closeable.close()
            }
        }
    }

    private fun fetchViewerState(): PdfViewerUiState? {
        var snapshot: PdfViewerUiState? = null
        activityScenario.onActivity { activity ->
            snapshot = activity.currentDocumentStateForTest()
        }
        snapshot?.let { logHarnessStateChange("snapshot", it) }
        return snapshot
    }

    private fun logHarnessStateChange(source: String, state: PdfViewerUiState) {
        val previous = lastLoggedUiState
        if (previous != null && previous == state) {
            return
        }
        lastLoggedUiState = state

        val deltas = mutableListOf<String>()
        if (previous?.currentPage != state.currentPage) deltas += "page"
        if (previous?.pageCount != state.pageCount) deltas += "pageCount"
        if (previous?.documentStatus != state.documentStatus) deltas += "documentStatus"
        if (previous?.renderProgress != state.renderProgress) deltas += "renderProgress"
        if (previous?.uiUnderLoad != state.uiUnderLoad) deltas += "uiUnderLoad"
        if (previous?.renderQueueStats != state.renderQueueStats) deltas += "renderQueue"
        if (previous?.bitmapMemory != state.bitmapMemory) deltas += "bitmap"
        if (previous?.malformedPages != state.malformedPages) deltas += "malformedPages"
        if (previous?.searchIndexing != state.searchIndexing) deltas += "searchIndexing"

        val rawDocumentId = state.documentId ?: if (::documentUri.isInitialized) {
            documentUri.toString()
        } else {
            null
        }
        val sanitizedDocumentId = rawDocumentId?.let { value ->
            sanitizeCacheFileName(
                raw = value,
                fallback = value,
                label = "state_log_document",
            ).ifEmpty { value }
        }

        val fields = mutableListOf<LogField>()
        fields += field("source", source)
        fields += field("documentId", sanitizedDocumentId)
        fields += field("pageIndex", state.currentPage)
        fields += field("pageNumber", state.currentPage + 1)
        fields += field("pageCount", state.pageCount)
        fields += field("documentStatus", state.documentStatus.javaClass.simpleName)
        fields += field("renderProgress", state.renderProgress.javaClass.simpleName)
        fields += field("uiUnderLoad", state.uiUnderLoad)
        fields += field("delta", if (deltas.isEmpty()) "none" else deltas.joinToString(","))
        fields += field("renderActive", state.renderQueueStats.active)
        fields += field("renderQueued", state.renderQueueStats.totalQueued)
        fields += field("bitmapBytes", state.bitmapMemory.currentBytes)
        fields += field("bitmapLevel", state.bitmapMemory.level.name)
        fields += field("malformedPages", state.malformedPages.size)
        fields += field("annotations", state.activeAnnotations.size)
        fields += field("bookmarks", state.bookmarks.size)
        fields += field("searchResults", state.searchResults.size)
        fields += field("searchIndexing", state.searchIndexing.javaClass.simpleName)
        fields += field("devDiagnostics", state.devDiagnosticsEnabled)
        fields += field("fallbackMode", state.fallbackMode.name)

        logHarnessInfo("Harness viewer state change", *fields.toTypedArray())
    }

    private suspend fun confirmInteractiveUiState(
        timeoutMs: Long = UI_READY_CONFIRMATION_TIMEOUT_MS,
    ): PdfViewerUiState {
        val startTime = SystemClock.elapsedRealtime()
        var lastLog = startTime
        var lastState: PdfViewerUiState? = null
        var underLoadCandidateStart = 0L
        var underLoadSnapshot: PdfViewerUiState? = null

        return try {
            withTimeout(timeoutMs) {
                viewerStateFlow()
                    .onEach { state ->
                        lastState = state
                        val now = SystemClock.elapsedRealtime()
                        val interactive = isUiInteractive(state)
                        if (interactive) {
                            underLoadCandidateStart = 0L
                            underLoadSnapshot = null
                            return@onEach
                        }

                        if (state.uiUnderLoad && isUiInteractiveIgnoringLoad(state)) {
                            if (underLoadCandidateStart == 0L) {
                                underLoadCandidateStart = now
                                underLoadSnapshot = state
                            }
                            if (now - lastLog >= 5_000L) {
                                logHarnessInfo(
                                    "Waiting for interactive UI confirmation; " +
                                        "status=${state.documentStatus.javaClass.simpleName} " +
                                        "renderProgress=${state.renderProgress} uiUnderLoad=${state.uiUnderLoad} " +
                                        "pageCount=${state.pageCount} " +
                                        "underLoadForMs=${now - underLoadCandidateStart}"
                                )
                                lastLog = now
                            }
                        } else {
                            underLoadCandidateStart = 0L
                            underLoadSnapshot = null
                            if (now - lastLog >= 5_000L) {
                                logHarnessInfo(
                                    "Waiting for interactive UI confirmation; " +
                                        "status=${state.documentStatus.javaClass.simpleName} " +
                                        "renderProgress=${state.renderProgress} uiUnderLoad=${state.uiUnderLoad} " +
                                        "pageCount=${state.pageCount}"
                                )
                                lastLog = now
                            }
                        }
                    }
                    .first { state ->
                        val interactive = isUiInteractive(state)
                        if (interactive) {
                            lastState = state
                            underLoadCandidateStart = 0L
                            underLoadSnapshot = null
                            return@first true
                        }

                        if (state.uiUnderLoad && isUiInteractiveIgnoringLoad(state)) {
                            val now = SystemClock.elapsedRealtime()
                            if (underLoadCandidateStart == 0L) {
                                underLoadCandidateStart = now
                                underLoadSnapshot = state
                            }
                            val elapsed = now - underLoadCandidateStart
                            if (elapsed >= UI_READY_UNDER_LOAD_GRACE_PERIOD_MS) {
                                uiReadyUnderLoadAccepted = true
                                val snapshot = underLoadSnapshot ?: state
                                val detail =
                                    "elapsedMs=$elapsed status=${snapshot.documentStatus.javaClass.simpleName} " +
                                        "renderProgress=${snapshot.renderProgress} pageCount=${snapshot.pageCount}"
                                logHarnessWarn(
                                    "Accepting interactive UI state despite sustained load; $detail"
                                )
                                recordHarnessProgress(
                                    HarnessProgressStep.UI_READY_UNDER_LOAD_ACCEPTED,
                                    detail
                                )
                                lastState = state
                                return@first true
                            }
                        }
                        false
                    }
            }
        } catch (error: TimeoutCancellationException) {
            val snapshot = lastState
            val message =
                "Timed out confirming interactive ReaderActivity for screenshot readiness " +
                    "(status=${snapshot?.documentStatus?.javaClass?.simpleName} " +
                    "renderProgress=${snapshot?.renderProgress} uiUnderLoad=${snapshot?.uiUnderLoad} " +
                    "pageCount=${snapshot?.pageCount})"
            val wrapped = IllegalStateException(message, error)
            logHarnessError(wrapped.message ?: "Timed out confirming interactive ReaderActivity", wrapped)
            publishHarnessFailureFlag("ui_ready_timeout", wrapped)
            throw wrapped
        }
    }

    private fun isUiInteractive(state: PdfViewerUiState): Boolean {
        if (!isUiInteractiveIgnoringLoad(state)) {
            return false
        }
        if (state.uiUnderLoad) {
            return false
        }
        return true
    }

    private fun isUiInteractiveIgnoringLoad(state: PdfViewerUiState): Boolean {
        if (state.pageCount <= 0) {
            return false
        }
        if (state.documentStatus !is DocumentStatus.Idle) {
            return false
        }
        if (state.renderProgress is PdfRenderProgress.Rendering) {
            return false
        }
        return true
    }

    @After
    fun tearDown() {
        try {
            runBlocking {
                if (!harnessEnabled || !::handshakeCacheDirs.isInitialized) {
                    return@runBlocking
                }
                var thresholdError: Throwable? = null
                metricsRecorder?.finish()?.let { report ->
                    publishPerformanceMetrics(report)
                    try {
                        enforcePerformanceThresholds(report)
                    } catch (error: Throwable) {
                        thresholdError = error
                    }
                }
                metricsRecorder = null
                cancelWorkManagerJobs()
                withContext(Dispatchers.IO) { cleanupFlags(deleteStatusArtifacts = false) }
                thresholdError?.let { throw it }
            }
        } finally {
            if (this::activityScenario.isInitialized) {
                activityScenario.close()
            }
        }
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
        recordHarnessProgress(
            HarnessProgressStep.HANDSHAKE_STARTED,
            "readyFlags=${readyFlags.joinToString { it.absolutePath }} " +
                "doneFlags=${doneFlags.joinToString { it.absolutePath }}"
        )

        withContext(Dispatchers.IO) {
            doneFlags.forEach { flag ->
                if (!clearFlag(flag)) {
                    logHarnessWarn(
                        "Unable to clear stale screenshot completion flag at ${flag.absolutePath}; continuing with existing flag"
                    )
                }
            }
        }

        try {
            val readinessState = confirmReadinessForScreenshots()
            if (!isUiInteractive(readinessState)) {
                if (uiReadyUnderLoadAccepted && isUiInteractiveIgnoringLoad(readinessState)) {
                    logHarnessWarn(
                        "Proceeding with screenshot readiness despite sustained UI load; " +
                            "renderProgress=${readinessState.renderProgress} page=${readinessState.currentPage + 1}/${readinessState.pageCount}"
                    )
                } else {
                val error = IllegalStateException(
                    "Unable to confirm interactive ReaderActivity before publishing readiness flag"
                )
                logHarnessError(error.message ?: "Unable to confirm interactive ReaderActivity", error)
                publishHarnessFailureFlag("ui_ready_regressed", error)
                throw error
                }
            }

            publishReadyFlags(readyFlags, readinessState)
            metricsRecorder?.markScreenshotCaptureStarted()

            if (programmaticScreenshotsEnabled) {
                captureProgrammaticScreenshot(doneFlags)
                metricsRecorder?.markScreenshotCaptureFinished()
                recordHarnessProgress(
                    HarnessProgressStep.COMPLETED,
                    "programmaticCapture=true"
                )
                return
            }
            logHarnessInfo(
                "Waiting for screenshot harness completion signal at ${doneFlags.joinToString { it.absolutePath }}"
            )
            recordHarnessProgress(
                HarnessProgressStep.WAITING_FOR_COMPLETION,
                "directories=${doneFlags.joinToString { it.absolutePath }}"
            )

            val start = System.currentTimeMillis()
            val completedFlag = awaitScreenshotCompletionFlag(readyFlags, doneFlags, start)
            metricsRecorder?.markScreenshotCaptureFinished()
            recordHarnessProgress(
                HarnessProgressStep.SCREENSHOT_CAPTURED,
                "flag=${completedFlag.absolutePath}"
            )

            withContext(Dispatchers.IO) {
                doneFlags.forEach { flag ->
                    deleteHandshakeFlag(flag, failOnError = flag == completedFlag)
                }
                readyFlags.forEach { flag ->
                    deleteHandshakeFlag(flag, failOnError = false)
                }
            }
            logHarnessInfo("Screenshot harness handshake completed; flags cleared")
            recordHarnessProgress(HarnessProgressStep.COMPLETED, "handshakeCompleted=true")
        } catch (error: Throwable) {
            if (error is AssumptionViolatedException || error is CancellationException) {
                throw error
            }
            if (!failureFlagPublished) {
                publishHarnessFailureFlag("handshake_error", error)
            } else {
                recordHarnessProgress(
                    HarnessProgressStep.FAILURE,
                    "handshake_error:${error.javaClass.simpleName}"
                )
            }
            throw error
        }
    }

    private suspend fun confirmReadinessForScreenshots(): PdfViewerUiState {
        uiReadyUnderLoadAccepted = false
        val initialState = confirmInteractiveUiState()
        recordHarnessProgress(
            HarnessProgressStep.UI_READY_OBSERVED,
            "page=${initialState.currentPage + 1}/${initialState.pageCount} " +
                "status=${initialState.documentStatus.javaClass.simpleName} " +
                "renderProgress=${initialState.renderProgress}"
        )
        logHarnessInfo(
            "Confirmed interactive ReaderActivity; page=${initialState.currentPage + 1}/${initialState.pageCount}. " +
                "Waiting up to $DEVICE_IDLE_TIMEOUT_MS ms for device idle before publishing readiness flag"
        )
        recordHarnessProgress(
            HarnessProgressStep.DEVICE_IDLE_WAITING,
            "timeoutMs=$DEVICE_IDLE_TIMEOUT_MS"
        )
        device.waitForIdle(DEVICE_IDLE_TIMEOUT_MS)
        recordHarnessProgress(
            HarnessProgressStep.DEVICE_IDLE_CONFIRMED,
            "timeoutMs=$DEVICE_IDLE_TIMEOUT_MS"
        )

        val postIdleState = fetchViewerState()
        val readinessState = when {
            postIdleState != null && isUiInteractive(postIdleState) -> postIdleState
            else -> {
                logHarnessWarn(
                    "UI state changed while waiting for device idle; revalidating interactive state before publishing readiness flag"
                )
                recordHarnessProgress(
                    HarnessProgressStep.UI_READY_REVALIDATED,
                    "retrying_with_timeout=$UI_READY_CONFIRMATION_TIMEOUT_MS"
                )
                try {
                    uiReadyUnderLoadAccepted = false
                    confirmInteractiveUiState()
                } catch (error: Throwable) {
                    if (error is AssumptionViolatedException || error is CancellationException) {
                        throw error
                    }
                    logHarnessError(
                        error.message
                            ?: "Unable to confirm interactive UI state after device idle wait",
                        error
                    )
                    publishHarnessFailureFlag("ui_ready_revalidation_failed", error)
                    throw error
                }
            }
        }

        recordHarnessProgress(
            HarnessProgressStep.UI_READY_CONFIRMED,
            "page=${readinessState.currentPage + 1}/${readinessState.pageCount} " +
                "status=${readinessState.documentStatus.javaClass.simpleName} " +
                "renderProgress=${readinessState.renderProgress}"
        )
        HarnessTestPoints.emit(
            HarnessTestPoint.UI_LOADED,
            "page=${readinessState.currentPage + 1}/${readinessState.pageCount}"
        )
        return readinessState
    }

    private suspend fun publishReadyFlags(
        readyFlags: List<File>,
        interactiveState: PdfViewerUiState,
    ) {
        val screenshotTarget = resolveScreenshotTarget(interactiveState)
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
        val readyFlagResults = withContext(Dispatchers.IO) {
            readyFlags.map { flag ->
                val success = writeHandshakeFlag(flag, readyPayload)
                if (!success) {
                    logHarnessWarn(
                        "Unable to write screenshot ready flag to ${flag.absolutePath}; continuing without this location"
                    )
                }
                success
            }
        }

        val readySuccessCount = readyFlagResults.count { it }
        val detail = buildString {
            append("payload=$readyPayload successes=$readySuccessCount/${readyFlags.size}")
            screenshotTarget?.let {
                append(' ')
                append(
                    "page=${it.pageNumber}/${it.pageCount}"
                )
            }
        }
        recordHarnessProgress(
            HarnessProgressStep.READY_PUBLISHED,
            detail
        )
        HarnessTestPoints.emit(HarnessTestPoint.READY_FOR_SCREENSHOT, detail)

        if (readyFlags.isEmpty() || (readySuccessCount == 0 && readyFlags.none(::flagExists))) {
            val error = IllegalStateException(
                "Screenshot harness failed to publish readiness flag to any cache directory"
            )
            logHarnessError(error.message ?: "Screenshot harness failed to publish readiness flag", error)
            publishHarnessFailureFlag("ready_flag_write_failed", error)
            throw error
        }
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
                    activityRunning = activityScenario.state.isAtLeast(Lifecycle.State.STARTED)
                }
                if (!activityRunning) {
                    val error = IllegalStateException(
                        "ReaderActivity unexpectedly stopped while waiting for screenshots"
                    )
                    logHarnessError(error.message ?: "ReaderActivity stopped unexpectedly", error)
                    publishHarnessFailureFlag("activity_stopped", error)
                    finishWithError(error)
                    return
                }
                if (elapsed > screenshotCompletionTimeoutMs) {
                    val error = IllegalStateException("Timed out waiting for host screenshot completion signal")
                    logHarnessError(error.message ?: "Timed out waiting for screenshot completion", error)
                    publishHarnessFailureFlag("screenshot_wait_timeout", error)
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
            appendLine("time_to_document_open,${report.timeToDocumentOpenMs},ms,")
            appendLine("time_to_first_page,${report.timeToFirstPageMs},ms,")
            report.screenshotDurationMs?.let { duration ->
                appendLine("screenshot_capture_duration,$duration,ms,")
            }
            val peakBytes = report.peakTotalPssKb.toLong() * 1024L
            appendLine("peak_memory,${peakBytes},bytes,source=totalPss")
            appendLine(
                "dropped_frames,${report.droppedFrames},frames,total_frames=${report.totalFrames}"
            )
            report.droppedFramePercent?.let { percent ->
                appendLine(
                    "dropped_frame_percent," +
                        "${String.format(Locale.US, "%.2f", percent)},percent,total_frames=${report.totalFrames}"
                )
            }
            report.averageFps?.let { fps ->
                appendLine(
                    "average_fps," +
                        "${String.format(Locale.US, "%.2f", fps)},fps,elapsed_ms=${report.elapsedMs}"
                )
            }
        }
        val directories = if (::handshakeCacheDirs.isInitialized && handshakeCacheDirs.isNotEmpty()) {
            handshakeCacheDirs
        } else {
            listOfNotNull(if (::appContext.isInitialized) appContext.cacheDir else null)
        }
        directories.forEach { directory ->
            val outputFile = File(directory, CacheFileNames.PERFORMANCE_METRICS_FILE)
            if (writeHandshakeFlag(outputFile, csv)) {
                val summary = buildString {
                    append("Wrote performance metrics to ${outputFile.absolutePath} ")
                    append(
                        "(docOpen=${report.timeToDocumentOpenMs}ms firstPage=${report.timeToFirstPageMs}ms"
                    )
                    report.screenshotDurationMs?.let { duration ->
                        append(" screenshot=${duration}ms")
                    }
                    append(" peakPss=${report.peakTotalPssKb}KiB")
                    append(" dropped=${report.droppedFrames}/${report.totalFrames}")
                    report.droppedFramePercent?.let { percent ->
                        append(String.format(Locale.US, " (%.2f%%)", percent))
                    }
                    report.averageFps?.let { fps ->
                        append(String.format(Locale.US, " avgFps=%.2f", fps))
                    }
                    append(')')
                }
                logHarnessInfo(summary)
            } else {
                logHarnessWarn(
                    "Unable to write performance metrics to ${outputFile.absolutePath}"
                )
            }
        }
    }

    private fun enforcePerformanceThresholds(report: PerformanceMetricsReport) {
        val violations = mutableListOf<String>()

        if (report.timeToDocumentOpenMs > MAX_TIME_TO_DOCUMENT_OPEN_MS) {
            violations +=
                "time_to_document_open=${report.timeToDocumentOpenMs}ms (threshold=${MAX_TIME_TO_DOCUMENT_OPEN_MS}ms)"
        }
        if (report.timeToFirstPageMs > MAX_TIME_TO_FIRST_PAGE_MS) {
            violations +=
                "time_to_first_page=${report.timeToFirstPageMs}ms (threshold=${MAX_TIME_TO_FIRST_PAGE_MS}ms)"
        }
        report.screenshotDurationMs?.let { duration ->
            if (duration > MAX_SCREENSHOT_CAPTURE_MS) {
                violations +=
                    "screenshot_capture_duration=${duration}ms (threshold=${MAX_SCREENSHOT_CAPTURE_MS}ms)"
            }
        } ?: logHarnessInfo(
            "Screenshot duration metric unavailable; skipping screenshot threshold evaluation"
        )

        val dropPercent = report.droppedFramePercent
        if (dropPercent != null) {
            if (dropPercent > MAX_DROPPED_FRAME_PERCENT) {
                violations += String.format(
                    Locale.US,
                    "dropped_frame_percent=%.2f%% (threshold=%.2f%%)",
                    dropPercent,
                    MAX_DROPPED_FRAME_PERCENT,
                )
            }
        } else {
            logHarnessInfo("Frame metrics unavailable; skipping dropped frame threshold evaluation")
        }

        val averageFps = report.averageFps
        if (averageFps != null) {
            if (averageFps < MIN_AVERAGE_FPS) {
                violations += String.format(
                    Locale.US,
                    "average_fps=%.2f (threshold=%.2f)",
                    averageFps,
                    MIN_AVERAGE_FPS,
                )
            }
        } else {
            logHarnessInfo("Frame metrics unavailable; skipping FPS threshold evaluation")
        }

        if (violations.isNotEmpty()) {
            val message =
                "Screenshot harness performance thresholds violated: ${violations.joinToString(separator = "; ")}"
            val error = AssertionError(message)
            publishHarnessFailureFlag("performance_threshold", error, fatal = false)
            throw error
        }

        val summary = buildString {
            append("Performance thresholds satisfied (docOpen=${report.timeToDocumentOpenMs}ms")
            append(", firstPage=${report.timeToFirstPageMs}ms")
            report.screenshotDurationMs?.let { duration ->
                append(", screenshot=${duration}ms")
            }
            dropPercent?.let {
                append(String.format(Locale.US, ", dropped=%.2f%%", it))
            }
            averageFps?.let {
                append(String.format(Locale.US, ", avgFps=%.2f", it))
            }
            append(')')
        }
        logHarnessInfo(summary)
    }

    private fun cleanupFlags(deleteStatusArtifacts: Boolean) {
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
            if (deleteStatusArtifacts) {
                deleteHandshakeFlag(
                    File(directory, CacheFileNames.SCREENSHOT_FAILED_FLAG),
                    failOnError = false
                )
                deleteHandshakeFlag(
                    File(directory, CacheFileNames.SCREENSHOT_SKIPPED_FLAG),
                    failOnError = false
                )
                deleteHandshakeFlag(
                    File(directory, CacheFileNames.SCREENSHOT_CRASHED_FLAG),
                    failOnError = false
                )
                deleteHandshakeFlag(
                    File(directory, CacheFileNames.SCREENSHOT_STATUS_FILE),
                    failOnError = false
                )
            }
        }
        if (deleteStatusArtifacts) {
            failureFlagPublished = false
            crashFlagPublished = false
        }
    }

    private fun writeHandshakeFlag(flag: File, contents: String): Boolean {
        val parent = flag.parentFile
        if (parent != null && !ensureHandshakeDirectory(parent)) {
            val error = IOException(
                "Unable to create directory for handshake flag at ${parent.absolutePath}"
            )
            logHarnessError(error.message ?: "Unable to create handshake directory", error)
            return false
        }

        return try {
            flag.writeText(contents, Charsets.UTF_8)
            true
        } catch (error: SecurityException) {
            val wrapped = IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
            logHarnessError(wrapped.message ?: "Failed to create handshake flag", wrapped)
            writeFlagWithRunAs(flag, contents)
        } catch (error: IOException) {
            val wrapped = IOException("Failed to create handshake flag at ${flag.absolutePath}", error)
            logHarnessError(wrapped.message ?: "Failed to create handshake flag", wrapped)
            writeFlagWithRunAs(flag, contents)
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
                publishHarnessFailureFlag("delete_flag_failed:${flag.name}", error)
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

            fun resolveCacheDir(label: String, provider: () -> File?): File? {
                return runCatching(provider)
                    .onFailure { error ->
                        logHarnessWarn(
                            "Unable to access $label for screenshot handshake fallback", error
                        )
                    }
                    .getOrNull()
            }

            val fallbackDirectories = listOfNotNull(
                resolveCacheDir("instrumentation.context.cacheDir") { instrumentation.context.cacheDir },
                resolveCacheDir("instrumentation.context.applicationContext.cacheDir") {
                    instrumentation.context.applicationContext?.cacheDir
                },
                resolveCacheDir("instrumentation.targetContext.cacheDir") { instrumentation.targetContext.cacheDir },
                resolveCacheDir("instrumentation.targetContext.applicationContext.cacheDir") {
                    instrumentation.targetContext.applicationContext?.cacheDir
                },
            )
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
        val packageManager = instrumentation.context.packageManager
        val knownPackages = buildSet {
            fun MutableSet<String>.addIfValid(value: String?) {
                if (!value.isNullOrBlank()) {
                    add(value)
                }
            }

            addIfValid(instrumentation.context.packageName)
            addIfValid(instrumentation.context.applicationContext?.packageName)
            val targetPackage = instrumentation.targetContext.packageName
            addIfValid(targetPackage)
            addIfValid(instrumentation.targetContext.applicationContext?.packageName)
            addIfValid("$targetPackage.test")
        }

        fun selectCandidate(candidate: String?, source: String): String? {
            val raw = candidate?.takeIf { it.isNotBlank() } ?: return null
            val value = raw.trim()
            val normalized = normalizePackageName(packageManager, value, knownPackages)
            if (normalized != null) {
                if (normalized != value) {
                    logHarnessInfo(
                        "Resolved sanitized screenshot harness package $value to $normalized from $source"
                    )
                }
                return normalized
            }
            logHarnessWarn("Ignoring invalid screenshot harness package name from $source: $value")
            return null
        }

        selectCandidate(arguments.getString("testPackageName"), "testPackageName argument")
            ?.let { return it }

        selectCandidate(
            arguments.getString("targetInstrumentation")?.substringBefore('/'),
            "targetInstrumentation argument"
        )?.let { return it }

        selectCandidate(arguments.getString("novapdfTestAppId"), "manifest placeholder")
            ?.let { return it }

        val manifestPlaceholder = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getInstrumentationInfo(
                instrumentation.componentName,
                PackageManager.GET_META_DATA
            ).metaData?.getString("novapdfTestAppId")
        }.getOrNull()
        selectCandidate(manifestPlaceholder, "instrumentation meta-data")?.let { return it }

        val instrumentationPackage = instrumentation.context.packageName
        normalizePackageName(packageManager, instrumentationPackage, knownPackages)?.let { packageName ->
            if (packageName.endsWith(".test")) {
                return packageName
            }
        } ?: run {
            logHarnessWarn(
                "Ignoring invalid instrumentation context package name: $instrumentationPackage"
            )
        }

        val targetPackage = instrumentation.targetContext.packageName
        val derived = normalizePackageName(packageManager, instrumentationPackage, knownPackages)
            ?.takeIf { it.isNotBlank() && it != targetPackage }
            ?: normalizePackageName(packageManager, "$targetPackage.test", knownPackages)

        selectCandidate(derived, "derived fallback")?.let { return it }

        return normalizePackageName(packageManager, targetPackage, knownPackages) ?: targetPackage
    }

    private fun normalizePackageName(
        packageManager: PackageManager,
        raw: String?,
        knownPackages: Collection<String>,
    ): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (PACKAGE_NAME_PATTERN.matches(value)) {
            return value
        }
        if (value.contains('*')) {
            knownPackages.firstOrNull { candidate -> matchesPackagePattern(value, candidate) }?.let { return it }
        }
        resolveSanitizedPackageName(packageManager, value)?.let { return it }
        val sanitized = value.replace(Regex("[^A-Za-z0-9._]"), "")
        if (sanitized.isNotEmpty() && PACKAGE_NAME_PATTERN.matches(sanitized)) {
            if (knownPackages.contains(sanitized)) {
                return sanitized
            }
            val resolved = runCatching { packageManager.getApplicationInfo(sanitized, 0) }.getOrNull()
            if (resolved != null) {
                return sanitized
            }
        }
        return null
    }

    private fun matchesPackagePattern(pattern: String, packageName: String): Boolean {
        if (!pattern.contains('*')) {
            return pattern == packageName
        }
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$")
        return regex.matches(packageName)
    }

    private fun resolveSanitizedPackageName(
        packageManager: PackageManager,
        pattern: String
    ): String? {
        if (!pattern.contains('*')) {
            return null
        }
        val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$")
        val installedPackages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }
        }.getOrElse { return null }
        val matches = installedPackages
            .mapNotNull { it.packageName }
            .filter { regex.matches(it) }
        if (matches.isEmpty()) {
            return null
        }
        if (matches.size == 1) {
            return matches.first()
        }
        val suffix = pattern.substringAfterLast('*', "")
        return matches.firstOrNull { suffix.isNotEmpty() && it.endsWith(suffix) } ?: matches.first()
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
        val remoteSource = documentSourceOverride
        if (remoteSource != null) {
            logHarnessInfo(
                "Opening remote document in viewer: ${remoteSource.kind}:${remoteSource.id}"
            )
        } else {
            logHarnessInfo("Opening thousand-page document in viewer: $documentUri")
        }
        metricsRecorder?.start(activityScenario)
        activityScenario.onActivity { activity ->
            if (remoteSource != null) {
                activity.openRemoteDocumentForTest(remoteSource)
            } else {
                activity.openDocumentForTest(documentUri)
            }
        }
        val progressDetail = buildString {
            append("uri=$documentUri")
            remoteSource?.let { append(", source=${it.kind}:${it.id}") }
        }
        recordHarnessProgress(
            HarnessProgressStep.PAGE_OPEN_REQUESTED,
            progressDetail
        )

        val deadline = SystemClock.elapsedRealtime() + DOCUMENT_OPEN_TIMEOUT
        var lastLog = SystemClock.elapsedRealtime()
        var firstPageLogged = false
        while (SystemClock.elapsedRealtime() < deadline) {
            var errorMessage: String? = null
            val state = fetchViewerState()
            val documentReady = if (state != null) {
                when (val status = state.documentStatus) {
                    is DocumentStatus.Error -> {
                        errorMessage = status.message
                        false
                    }
                    is DocumentStatus.Loading -> false
                    DocumentStatus.Idle -> isUiInteractive(state)
                }
            } else {
                false
            }
            if (!firstPageLogged && (state?.pageCount ?: 0) > 0) {
                metricsRecorder?.markFirstPageParsed()
                recordHarnessProgress(
                    HarnessProgressStep.PAGE_OPENED,
                    "page=${(state?.currentPage ?: 0) + 1}/${state?.pageCount}"
                )
                firstPageLogged = true
            }
            metricsRecorder?.sample()

            errorMessage?.let { message ->
                if (state != null) {
                    logHarnessWarn(
                        "Document load reported error with state=${state.documentStatus.javaClass.simpleName} " +
                            "pageCount=${state.pageCount} renderProgress=${state.renderProgress}"
                    )
                }
                val error = IllegalStateException("Failed to load document for screenshots: $message")
                logHarnessError(error.message ?: "Failed to load document for screenshots", error)
                publishHarnessFailureFlag("document_load_error:$message", error)
                throw error
            }
            if (documentReady) {
                metricsRecorder?.markDocumentOpened()
                metricsRecorder?.sample()
                logHarnessInfo(
                    "Thousand-page document finished loading with pageCount=${state?.pageCount}"
                )
                recordHarnessProgress(
                    HarnessProgressStep.RENDER_COMPLETE,
                    "page=${(state?.currentPage ?: 0) + 1}/${state?.pageCount} renderProgress=${state?.renderProgress}"
                )
                device.waitForIdle(DEVICE_IDLE_TIMEOUT_MS)
                recordHarnessProgress(
                    HarnessProgressStep.UI_INTERACTIVE,
                    "deviceIdleTimeoutMs=$DEVICE_IDLE_TIMEOUT_MS"
                )
                return
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastLog >= 5_000L) {
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
        publishHarnessFailureFlag("document_load_timeout", error)
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
                val error = IllegalStateException("Programmatic screenshot capture returned null bitmap")
                logHarnessError(error.message ?: "Programmatic screenshot capture returned null bitmap", error)
                publishHarnessFailureFlag("programmatic_screenshot_null", error)
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
                    publishHarnessFailureFlag("programmatic_screenshot_persist", error)
                    throw error
                }
            }
            logHarnessInfo("Programmatic screenshot saved to ${screenshotFile.absolutePath}")

            val publishedFlags = withContext(Dispatchers.IO) {
                doneFlags.map { flag ->
                    val success = writeHandshakeFlag(flag, screenshotFile.absolutePath)
                    if (!success) {
                        logHarnessWarn(
                            "Unable to publish programmatic screenshot completion flag at ${flag.absolutePath}"
                        )
                    }
                    success
                }
            }
            if (!publishedFlags.any { it }) {
                logHarnessWarn("Programmatic screenshot completion flag was not published to any cache directory")
                publishHarnessFailureFlag("programmatic_done_flag_missing", IllegalStateException("Screenshot completion flag missing"))
                return
            }
            recordHarnessProgress(
                HarnessProgressStep.SCREENSHOT_CAPTURED,
                "programmaticPath=${screenshotFile.absolutePath}"
            )
        } catch (error: SecurityException) {
            logHarnessError(
                error.message ?: "Security exception during programmatic screenshot capture",
                error
            )
            publishHarnessFailureFlag("programmatic_screenshot_security", error)
        } catch (error: OutOfMemoryError) {
            logHarnessError(
                error.message ?: "Out of memory during programmatic screenshot capture",
                error
            )
            publishHarnessFailureFlag("programmatic_screenshot_oom", error)
        } catch (error: IOException) {
            logHarnessError(
                error.message ?: "IO exception during programmatic screenshot capture",
                error
            )
            publishHarnessFailureFlag("programmatic_screenshot_io", error)
        } catch (error: RuntimeException) {
            logHarnessError(
                error.message ?: "Unexpected error during programmatic screenshot capture",
                error
            )
            publishHarnessFailureFlag("programmatic_screenshot_runtime", error)
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

    private fun recordHarnessProgress(step: HarnessProgressStep, detail: String? = null) {
        val sanitizedDetail = detail?.replace('\n', ' ')
        val message = buildString {
            append("Harness progress [${step.label}]")
            if (!sanitizedDetail.isNullOrBlank()) {
                append(": ")
                append(sanitizedDetail)
            }
        }
        lastProgressStep = step
        val fields = mutableListOf<LogField>()
        fields += field("progressStep", step.label)
        if (!sanitizedDetail.isNullOrBlank()) {
            fields += field("progressDetail", sanitizedDetail)
        }
        logHarnessInfo(message, *fields.toTypedArray())
        val directories = resolveStatusDirectories()
        if (directories.isEmpty()) {
            return
        }
        val entry = buildString {
            append(System.currentTimeMillis())
            append('\t')
            append(step.label)
            if (!sanitizedDetail.isNullOrBlank()) {
                append('\t')
                append(sanitizedDetail)
            }
        }
        directories.forEach { directory ->
            val statusFile = File(directory, CacheFileNames.SCREENSHOT_STATUS_FILE)
            runCatching {
                if (!ensureHandshakeDirectory(directory)) {
                    return@runCatching
                }
                statusFile.appendText(entry + "\n", Charsets.UTF_8)
            }.onFailure { error ->
                logHarnessWarn(
                    "Unable to record harness progress at ${statusFile.absolutePath}",
                    error
                )
            }
        }
    }

    private fun publishHarnessSkipFlag(
        reason: String,
        detail: String?,
        blacklistRunner: Boolean,
    ) {
        val sanitizedDetail = detail?.takeIf { it.isNotBlank() }
        val progressDetail = sanitizedDetail ?: reason
        recordHarnessProgress(HarnessProgressStep.SKIPPED, progressDetail)
        val directories = resolveStatusDirectories()
        if (directories.isEmpty()) {
            logHarnessWarn(
                "Unable to publish screenshot skip flag; no status directories available"
            )
            return
        }
        val payload = buildHarnessStatusPayload(
            status = "skipped",
            reason = reason,
            error = null,
            detail = sanitizedDetail,
            blacklistRunner = blacklistRunner,
        )
        directories.forEach { directory ->
            val flag = File(directory, CacheFileNames.SCREENSHOT_SKIPPED_FLAG)
            logHarnessInfo(
                "Writing screenshot skip flag to ${flag.absolutePath} with payload $payload",
            )
            val success = writeHandshakeFlag(flag, payload)
            if (!success) {
                logHarnessWarn(
                    "Unable to write screenshot skip flag to ${flag.absolutePath}"
                )
            }
        }
    }

    private fun publishHarnessFailureFlag(
        reason: String,
        error: Throwable?,
        fatal: Boolean = true,
    ) {
        val detail = buildHarnessStatusDetail(reason, error)
        recordHarnessProgress(HarnessProgressStep.FAILURE, detail)
        HarnessTestPoints.emit(HarnessTestPoint.ERROR_SIGNALED, detail)
        val directories = resolveStatusDirectories()
        if (directories.isNotEmpty()) {
            val payload = buildHarnessStatusPayload(
                status = "failed",
                reason = reason,
                error = error,
                detail = detail,
            )
            val published = directories.map { directory ->
                val flag = File(directory, CacheFileNames.SCREENSHOT_FAILED_FLAG)
                val success = writeHandshakeFlag(flag, payload)
                if (!success) {
                    logHarnessWarn(
                        "Unable to write screenshot failure flag to ${flag.absolutePath}"
                    )
                }
                success
            }
            if (published.any { it } &&
                ::handshakeCacheDirs.isInitialized &&
                handshakeCacheDirs.isNotEmpty() &&
                directories == handshakeCacheDirs
            ) {
                failureFlagPublished = true
            }
        }
        if (fatal) {
            publishHarnessCrashFlag(reason, error, detail, directories)
        }
    }

    private fun publishHarnessCrashFlag(
        reason: String,
        error: Throwable?,
        detail: String? = null,
        directoriesOverride: List<File>? = null,
    ) {
        val crashDetail = detail ?: buildHarnessStatusDetail(reason, error)
        recordHarnessProgress(HarnessProgressStep.CRASHED, crashDetail)
        val directories = directoriesOverride ?: resolveStatusDirectories()
        if (directories.isEmpty() ||
            (::handshakeCacheDirs.isInitialized && handshakeCacheDirs.isNotEmpty() && crashFlagPublished)
        ) {
            return
        }
        val payload = buildHarnessStatusPayload(
            status = "crashed",
            reason = reason,
            error = error,
            detail = crashDetail,
        )
        val published = directories.map { directory ->
            val flag = File(directory, CacheFileNames.SCREENSHOT_CRASHED_FLAG)
            val success = writeHandshakeFlag(flag, payload)
            if (!success) {
                logHarnessWarn(
                    "Unable to write screenshot crash flag to ${flag.absolutePath}"
                )
            }
            success
        }
        if (published.any { it } &&
            ::handshakeCacheDirs.isInitialized &&
            handshakeCacheDirs.isNotEmpty() &&
            directories == handshakeCacheDirs
        ) {
            crashFlagPublished = true
        }
    }

    private fun resolveStatusDirectories(): List<File> {
        if (::handshakeCacheDirs.isInitialized && handshakeCacheDirs.isNotEmpty()) {
            return handshakeCacheDirs
        }

        val directories = mutableListOf<File>()
        if (::appContext.isInitialized) {
            directories += File(appContext.cacheDir, CacheFileNames.HARNESS_CACHE_DIRECTORY)
            appContext.filesDir?.let { directory ->
                directories += File(directory, CacheFileNames.HARNESS_CACHE_DIRECTORY)
            }
            appContext.externalCacheDir?.let { directory ->
                directories += File(directory, CacheFileNames.HARNESS_CACHE_DIRECTORY)
            }
        }

        val instrumentation = runCatching { InstrumentationRegistry.getInstrumentation() }.getOrNull()
        if (instrumentation != null) {
            runCatching { instrumentation.context.cacheDir }
                .getOrNull()
                ?.let { directories += File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY) }
            runCatching { instrumentation.context.applicationContext?.cacheDir }
                .getOrNull()
                ?.let { directories += File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY) }
            runCatching { instrumentation.targetContext.cacheDir }
                .getOrNull()
                ?.let { directories += File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY) }
            runCatching { instrumentation.targetContext.applicationContext?.cacheDir }
                .getOrNull()
                ?.let { directories += File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY) }
        }

        return directories
            .mapNotNull { directory ->
                if (!prepareCacheDirectory(directory)) {
                    null
                } else {
                    runCatching { directory.canonicalFile }.getOrElse { directory }
                }
            }
            .distinctBy { it.absolutePath }
    }

    private fun buildHarnessStatusDetail(reason: String, error: Throwable?): String {
        val message = error?.message?.takeIf { it.isNotBlank() }
        return if (message != null) {
            "$reason: $message"
        } else {
            reason
        }
    }

    private fun buildHarnessStatusPayload(
        status: String,
        reason: String,
        error: Throwable?,
        detail: String? = null,
        blacklistRunner: Boolean = false,
    ): String {
        return try {
            JSONObject().apply {
                put("status", status)
                put("reason", reason)
                put("timestamp", System.currentTimeMillis())
                detail?.let { put("detail", it) }
                if (blacklistRunner) {
                    put("blacklistRunner", true)
                }
                error?.let {
                    put("exception", it.javaClass.name)
                    put("message", it.message ?: "")
                }
            }.toString()
        } catch (encodingError: Exception) {
            val message = error?.message ?: ""
            val components = mutableListOf(
                "status=$status",
                "reason=$reason",
                "message=$message",
                "timestamp=${System.currentTimeMillis()}",
            )
            if (!detail.isNullOrBlank()) {
                components += "detail=$detail"
            }
            if (blacklistRunner) {
                components += "blacklistRunner=true"
            }
            components.joinToString(";")
        }
    }

    private fun resolveScreenshotCompletionTimeoutMillis(context: Context): Long {
        deviceTimeouts = DeviceAdaptiveTimeouts.forContext(context)
        return deviceTimeouts.scaleTimeout(
            base = DEFAULT_SCREENSHOT_COMPLETION_TIMEOUT_MS,
            min = MIN_SCREENSHOT_COMPLETION_TIMEOUT_MS,
            max = MAX_SCREENSHOT_COMPLETION_TIMEOUT_MS,
            extraMultiplier = 1.1,
            allowTightening = false,
        )
    }

    private data class ScreenshotTarget(
        val documentId: String,
        val sanitizedDocumentId: String,
        val pageIndex: Int,
        val pageNumber: Int,
        val pageLabel: String,
        val pageCount: Int,
    )

    private enum class HarnessProgressStep(val label: String) {
        TEST_INITIALISED("test_initialised"),
        PAGE_OPEN_REQUESTED("page_open_requested"),
        PAGE_OPENED("page_open"),
        RENDER_COMPLETE("render_complete"),
        UI_INTERACTIVE("ui_interactive"),
        UI_READY_OBSERVED("ui_ready_observed"),
        DEVICE_IDLE_WAITING("device_idle_waiting"),
        DEVICE_IDLE_CONFIRMED("device_idle_confirmed"),
        UI_READY_REVALIDATED("ui_ready_revalidated"),
        HANDSHAKE_STARTED("handshake_started"),
        UI_READY_CONFIRMED("ui_ready_confirmed"),
        UI_READY_UNDER_LOAD_ACCEPTED("ui_ready_under_load"),
        READY_PUBLISHED("ready_flag_published"),
        WAITING_FOR_COMPLETION("waiting_for_completion"),
        SCREENSHOT_CAPTURED("screenshot"),
        COMPLETED("completed"),
        SKIPPED("skipped"),
        CRASHED("crashed"),
        FAILURE("failure"),
    }

    private fun harnessFields(vararg extra: LogField): Array<LogField> {
        val fields = mutableListOf<LogField>()
        fields += field("module", "harness")
        fields += field("timestampMs", System.currentTimeMillis())
        currentTestDescription?.let { testName -> fields += field("test", testName) }
        lastProgressStep?.let { step -> fields += field("harnessStep", step.label) }
        fields.addAll(staticDeviceFields)
        if (::deviceTimeouts.isInitialized) {
            fields.addAll(deviceTimeouts.toLogFields())
        }
        if (::device.isInitialized) {
            fields += field("deviceOrientation", device.displayRotation)
        }
        fields += field("programmaticScreenshots", programmaticScreenshotsEnabled)
        extra.forEach { field -> fields += field }
        return fields.toTypedArray()
    }

    private fun renderHarnessConsoleMessage(message: String, fields: Array<LogField>): String {
        if (fields.isEmpty()) {
            return message
        }
        return buildString(message.length + fields.size * 16) {
            append(message)
            fields.forEach { field ->
                append(" | ")
                append(field.render())
            }
        }
    }

    private fun printHarnessConsole(message: String, fields: Array<LogField>, error: Throwable?) {
        val formatted = renderHarnessConsoleMessage(message, fields)
        val instrumentation = runCatching { InstrumentationRegistry.getInstrumentation() }.getOrNull()
        if (instrumentation != null) {
            sendInstrumentationConsole(instrumentation, "$TAG: $formatted")
            if (error != null) {
                sendInstrumentationConsole(
                    instrumentation,
                    android.util.Log.getStackTraceString(error)
                )
            }
        }
        if (error != null) {
            println("$TAG: $formatted\n${android.util.Log.getStackTraceString(error)}")
        } else {
            println("$TAG: $formatted")
        }
    }

    private fun sendInstrumentationConsole(instrumentation: Instrumentation, message: String) {
        chunkInstrumentationMessage(message).forEach { chunk ->
            val bundle = Bundle().apply { putString("stream", chunk) }
            runCatching { instrumentation.sendStatus(0, bundle) }
        }
    }

    private fun chunkInstrumentationMessage(message: String): List<String> {
        if (message.length <= MAX_INSTRUMENTATION_STATUS_LENGTH) {
            return listOf(message)
        }
        val chunks = message.chunked(MAX_INSTRUMENTATION_STATUS_LENGTH)
        val total = chunks.size
        return chunks.mapIndexed { index, chunk ->
            if (index == 0) {
                chunk
            } else {
                buildString(chunk.length + 32) {
                    append(TAG)
                    append(": (chunk ")
                    append(index + 1)
                    append('/')
                    append(total)
                    append(") ")
                    append(chunk)
                }
            }
        }
    }

    private fun logHarnessInfo(message: String, vararg fields: LogField) {
        val enriched = harnessFields(*fields)
        NovaLog.i(tag = TAG, message = message, fields = enriched)
        printHarnessConsole(message, enriched, null)
    }

    private fun logHarnessWarn(message: String, error: Throwable? = null, vararg fields: LogField) {
        val enriched = harnessFields(*fields)
        NovaLog.w(tag = TAG, message = message, throwable = error, fields = enriched)
        printHarnessConsole(message, enriched, error)
    }

    private fun logHarnessError(message: String, error: Throwable, vararg fields: LogField) {
        val failureContext = HarnessFailureContext(
            testName = currentTestDescription,
            documentId = resolveFailureDocumentId(),
            pageIndex = lastLoggedUiState?.currentPage,
            pageCount = lastLoggedUiState?.pageCount,
            userAction = lastProgressStep?.label,
        )
        val failureFields = HarnessFailureMetadata.buildFields(
            reason = message,
            context = failureContext,
            error = error,
            includeDeviceStats = false,
        )
        val combinedFields = mutableListOf<LogField>()
        combinedFields.addAll(failureFields.toList())
        combinedFields.addAll(fields.asList())
        val enriched = harnessFields(*combinedFields.toTypedArray())
        NovaLog.e(tag = TAG, message = message, throwable = error, fields = enriched)
        printHarnessConsole(message, enriched, error)
    }

    private fun resolveFailureDocumentId(): String? {
        val stateDocumentId = lastLoggedUiState?.documentId
        val fallbackDocumentId = if (::documentUri.isInitialized) {
            documentUri.toString()
        } else {
            null
        }
        val rawDocumentId = stateDocumentId ?: fallbackDocumentId ?: return null
        return sanitizeCacheFileName(
            raw = rawDocumentId,
            fallback = rawDocumentId,
            label = "failure_document",
        ).ifEmpty { rawDocumentId }
    }

    class HarnessTestWatcher(
        private val onEvent: (String) -> Unit,
        private val onFailure: (String, Throwable) -> Unit,
        private val onLifecycleChange: (LifecycleEvent, Description) -> Unit = { _, _ -> },
    ) : TestWatcher() {

        enum class LifecycleEvent { STARTED, FINISHED }

        override fun starting(description: Description) {
            onLifecycleChange(LifecycleEvent.STARTED, description)
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
            onLifecycleChange(LifecycleEvent.FINISHED, description)
        }
    }

    private companion object {
        private const val HARNESS_ARGUMENT = "runScreenshotHarness"
        private const val PROGRAMMATIC_SCREENSHOTS_ARGUMENT = "captureProgrammaticScreenshots"
        private const val CAPTURE_VIDEO_OUTPUT_PERMISSION = "android.permission.CAPTURE_VIDEO_OUTPUT"
        private val DEFAULT_SCREENSHOT_COMPLETION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5)
        private val MIN_SCREENSHOT_COMPLETION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(2)
        private val MAX_SCREENSHOT_COMPLETION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(12)
        private const val FLAG_OBSERVER_INITIAL_BACKOFF_MS = 250L
        private const val FLAG_OBSERVER_MAX_BACKOFF_MS = 5_000L
        private const val MAX_TIME_TO_DOCUMENT_OPEN_MS = 120_000L
        private const val MAX_TIME_TO_FIRST_PAGE_MS = 45_000L
        private const val MAX_SCREENSHOT_CAPTURE_MS = 60_000L
        private const val MAX_DROPPED_FRAME_PERCENT = 15.0
        private const val MIN_AVERAGE_FPS = 30.0
        // Opening a thousand-page stress document can take a while on CI devices, so give the
        // viewer ample time to finish rendering before failing the harness run.
        private const val DOCUMENT_OPEN_TIMEOUT = 180_000L
        private const val WORK_MANAGER_CANCEL_TIMEOUT_SECONDS = 15L
        private const val DEVICE_IDLE_TIMEOUT_MS = 10_000L
        private const val UI_READY_CONFIRMATION_TIMEOUT_MS = 30_000L
        private const val UI_READY_UNDER_LOAD_GRACE_PERIOD_MS = 20_000L
        private const val PREFLIGHT_CPU_SAMPLE_INTERVAL_MS = 500L
        private const val PREFLIGHT_TOTAL_CPU_THRESHOLD_PERCENT = 90f
        private const val PREFLIGHT_OTHER_CPU_THRESHOLD_PERCENT = 80f
        private const val TAG = "ScreenshotHarness"
        private val PACKAGE_NAME_PATTERN = Regex("^[A-Za-z0-9._]+$")
        private const val MAX_INSTRUMENTATION_STATUS_LENGTH = 30_000
    }
}
