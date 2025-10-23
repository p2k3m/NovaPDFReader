package com.novapdf.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.math.max
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class DeviceResourceMonitorRule(
    private val contextProvider: () -> Context?,
    private val logger: (String) -> Unit,
    private val onResourceExhausted: (String) -> Unit,
    private val thresholds: ResourceThresholds = ResourceThresholds(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleeper: (Long) -> Unit = { durationMs ->
        if (durationMs > 0) {
            Thread.sleep(durationMs)
        }
    },
) : TestRule {

    data class ResourceThresholds(
        val minAvailableMemKb: Long = 256_000L,
        val maxSustainedCpuPercent: Float = 95f,
        val maxMemoryDropPercent: Float = 60f,
        val minCpuIncreasePercent: Float = 10f,
    )

    private data class ResourceSnapshot(
        val timestampMs: Long,
        val availMemKb: Long?,
        val totalMemKb: Long?,
        val lowMemory: Boolean,
        val cpuUsagePercent: Float?,
    )

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val before = captureSnapshot(description, phase = "before")
                var failure: Throwable? = null
                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    val after = captureSnapshot(description, phase = "after")
                    val exhaustionReason = evaluateSnapshots(before, after)
                    if (exhaustionReason != null) {
                        logger(
                            "Device resources exhausted for ${description.displayName}: $exhaustionReason",
                        )
                        onResourceExhausted(exhaustionReason)
                        if (failure == null) {
                            throw AssertionError("Device resources exhausted: $exhaustionReason")
                        }
                    }
                }
            }
        }
    }

    private fun captureSnapshot(description: Description, phase: String): ResourceSnapshot {
        val timestamp = clock()
        val context = runCatching { contextProvider() }.getOrNull()
        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        var availableMemKb: Long? = null
        var totalMemKb: Long? = null
        var lowMemory = false
        if (activityManager != null) {
            runCatching {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                availableMemKb = memoryInfo.availMem / 1024L
                totalMemKb = memoryInfo.totalMem / 1024L
                lowMemory = memoryInfo.lowMemory || isLowRamDevice(activityManager)
            }
        }
        val cpuUsage = sampleCpuUsagePercent()
        logger(
            buildString {
                append("Resource snapshot(")
                append(phase)
                append(") for ")
                append(description.displayName)
                append(": ")
                append(formatSnapshot(availableMemKb, totalMemKb, lowMemory, cpuUsage))
            },
        )
        return ResourceSnapshot(
            timestampMs = timestamp,
            availMemKb = availableMemKb,
            totalMemKb = totalMemKb,
            lowMemory = lowMemory,
            cpuUsagePercent = cpuUsage,
        )
    }

    private fun formatSnapshot(
        availableMemKb: Long?,
        totalMemKb: Long?,
        lowMemory: Boolean,
        cpuUsage: Float?,
    ): String {
        val memoryPart = when {
            availableMemKb != null && totalMemKb != null && totalMemKb > 0 -> {
                val percent = (availableMemKb.toDouble() / totalMemKb.toDouble()) * 100.0
                String.format(
                    Locale.US,
                    "RAM=%dMB free/%dMB total (%.1f%% free)",
                    availableMemKb / 1024L,
                    totalMemKb / 1024L,
                    percent,
                )
            }
            availableMemKb != null -> {
                String.format(Locale.US, "RAM=%dMB free", availableMemKb / 1024L)
            }
            else -> {
                "RAM=unknown"
            }
        }
        val lowMemoryPart = if (lowMemory) ", lowMemory=true" else ""
        val cpuPart = cpuUsage?.let { usage ->
            String.format(Locale.US, ", CPU=%.1f%%", usage)
        } ?: ""
        return memoryPart + lowMemoryPart + cpuPart
    }

    private fun evaluateSnapshots(before: ResourceSnapshot, after: ResourceSnapshot): String? {
        if (after.lowMemory) {
            return "system reported low-memory condition"
        }
        val available = after.availMemKb
        if (available != null && available < thresholds.minAvailableMemKb) {
            return "available RAM ${available / 1024L}MB below minimum ${thresholds.minAvailableMemKb / 1024L}MB"
        }
        val beforeAvail = before.availMemKb
        if (available != null && beforeAvail != null && beforeAvail > 0) {
            val drop = beforeAvail - available
            if (drop > 0) {
                val dropPercent = (drop.toDouble() / beforeAvail.toDouble()) * 100.0
                if (dropPercent > thresholds.maxMemoryDropPercent) {
                    return String.format(
                        Locale.US,
                        "available RAM dropped by %.1f%% (from %dMB to %dMB)",
                        dropPercent,
                        beforeAvail / 1024L,
                        available / 1024L,
                    )
                }
            }
        }
        val cpu = after.cpuUsagePercent
        if (cpu != null && cpu > thresholds.maxSustainedCpuPercent) {
            val beforeCpu = before.cpuUsagePercent
            if (beforeCpu != null) {
                val delta = cpu - beforeCpu
                if (delta < thresholds.minCpuIncreasePercent) {
                    return null
                }
            }
            return String.format(
                Locale.US,
                "CPU usage %.1f%% exceeds limit %.1f%%",
                cpu,
                thresholds.maxSustainedCpuPercent,
            )
        }
        return null
    }

    private fun sampleCpuUsagePercent(): Float? {
        val first = readCpuTimes() ?: return null
        sleeper(200)
        val second = readCpuTimes() ?: return null
        val deltaTotal = second.total - first.total
        val deltaIdle = second.idle - first.idle
        if (deltaTotal <= 0 || deltaIdle < 0) {
            return null
        }
        val usage = (deltaTotal - deltaIdle).toDouble() / deltaTotal.toDouble() * 100.0
        return usage.toFloat()
    }

    private data class CpuTimes(val idle: Long, val total: Long)

    private fun readCpuTimes(): CpuTimes? {
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
                CpuTimes(idle = idle, total = total)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun isLowRamDevice(activityManager: ActivityManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activityManager.isLowRamDevice
        } else {
            false
        }
    }
}
