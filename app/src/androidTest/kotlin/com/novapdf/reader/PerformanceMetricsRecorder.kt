package com.novapdf.reader

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.app.FrameMetricsAggregator
import androidx.test.core.app.ActivityScenario
import java.lang.ref.WeakReference
import kotlin.math.max

internal data class PerformanceMetricsReport(
    val timeToDocumentOpenMs: Long,
    val timeToFirstPageMs: Long,
    val screenshotDurationMs: Long?,
    val peakTotalPssKb: Int,
    val totalFrames: Int,
    val droppedFrames: Int,
    val elapsedMs: Long,
    val averageFps: Double?,
    val droppedFramePercent: Double?,
)

internal class PerformanceMetricsRecorder(
    context: Context,
    private val jankThresholdMs: Float = BuildConfig.ADAPTIVE_FLOW_JANK_FRAME_THRESHOLD_MS,
) {
    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val processId: Int = Process.myPid()
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
    private val frameMetricsAggregator: FrameMetricsAggregator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        FrameMetricsAggregator(FrameMetricsAggregator.TOTAL_DURATION)
    } else {
        null
    }

    private var trackedActivityRef: WeakReference<Activity>? = null
    private var monitoring: Boolean = false
    private var startTimeMs: Long = 0L
    private var timeToDocumentOpenMs: Long? = null
    private var timeToFirstPageMs: Long? = null
    private var peakPssKb: Int = 0
    private var totalFrames: Int = 0
    private var droppedFrames: Int = 0
    private var screenshotStartMs: Long? = null
    private var screenshotDurationMs: Long? = null

    fun start(scenario: ActivityScenario<out Activity>) {
        monitoring = true
        startTimeMs = clock()
        timeToFirstPageMs = null
        timeToDocumentOpenMs = null
        peakPssKb = sampleProcessPss()
        totalFrames = 0
        droppedFrames = 0
        screenshotStartMs = null
        screenshotDurationMs = null
        frameMetricsAggregator?.reset()
        scenario.onActivity { activity ->
            trackedActivityRef = WeakReference(activity)
            frameMetricsAggregator?.add(activity)
        }
    }

    fun sample() {
        if (!monitoring) {
            return
        }
        peakPssKb = max(peakPssKb, sampleProcessPss())
    }

    fun markFirstPageParsed() {
        if (!monitoring || timeToFirstPageMs != null) {
            return
        }
        timeToFirstPageMs = clock() - startTimeMs
    }

    fun markDocumentOpened() {
        if (!monitoring || timeToDocumentOpenMs != null) {
            return
        }
        timeToDocumentOpenMs = clock() - startTimeMs
    }

    fun markScreenshotCaptureStarted() {
        if (!monitoring) {
            return
        }
        screenshotStartMs = clock()
    }

    fun markScreenshotCaptureFinished() {
        if (!monitoring) {
            return
        }
        val start = screenshotStartMs ?: return
        screenshotDurationMs = clock() - start
    }

    fun finish(): PerformanceMetricsReport? {
        if (!monitoring) {
            return null
        }
        monitoring = false
        sample()
        collectFrameMetrics()
        val elapsed = clock() - startTimeMs
        val resolvedTimeToFirstPage = timeToFirstPageMs ?: elapsed
        val resolvedTimeToDocumentOpen = timeToDocumentOpenMs ?: elapsed
        val screenshotElapsed = screenshotDurationMs
        val averageFps = if (elapsed > 0 && totalFrames > 0) {
            totalFrames.toDouble() / (elapsed.toDouble() / 1000.0)
        } else {
            null
        }
        val droppedPercent = if (totalFrames > 0) {
            (droppedFrames.toDouble() / totalFrames.toDouble()) * 100.0
        } else {
            null
        }
        return PerformanceMetricsReport(
            timeToDocumentOpenMs = resolvedTimeToDocumentOpen,
            timeToFirstPageMs = resolvedTimeToFirstPage,
            screenshotDurationMs = screenshotElapsed,
            peakTotalPssKb = peakPssKb,
            totalFrames = totalFrames,
            droppedFrames = droppedFrames,
            elapsedMs = elapsed,
            averageFps = averageFps,
            droppedFramePercent = droppedPercent,
        )
    }

    private fun collectFrameMetrics() {
        val aggregator = frameMetricsAggregator ?: return
        val activity = trackedActivityRef?.get()
        val metrics = if (activity != null) {
            aggregator.remove(activity)
        } else {
            aggregator.metrics
        }
        aggregator.reset()
        trackedActivityRef = null
        totalFrames = 0
        droppedFrames = 0
        val totalDurations = metrics?.getOrNull(FrameMetricsAggregator.TOTAL_INDEX)
        if (totalDurations != null) {
            val threshold = jankThresholdMs.toInt()
            for (index in 0 until totalDurations.size()) {
                val durationMs = totalDurations.keyAt(index)
                val count = totalDurations.valueAt(index)
                totalFrames += count
                if (durationMs > threshold) {
                    droppedFrames += count
                }
            }
        }
    }

    private fun sampleProcessPss(): Int {
        val manager = activityManager ?: return 0
        return try {
            val info = manager.getProcessMemoryInfo(intArrayOf(processId))
            if (info.isNotEmpty()) info[0].totalPss else 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun <T> Array<T?>?.getOrNull(index: Int): T? {
        if (this == null || index < 0 || index >= size) {
            return null
        }
        return this[index]
    }
}
