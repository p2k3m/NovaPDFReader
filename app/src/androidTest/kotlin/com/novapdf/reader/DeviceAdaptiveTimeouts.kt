package com.novapdf.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.max
import kotlin.math.min

/**
 * Provides device-specific timeout scaling to accommodate emulators and low-powered hardware.
 */
class DeviceAdaptiveTimeouts private constructor(
    private val totalMemBytes: Long?,
    private val availMemBytes: Long?,
    private val lowMemory: Boolean,
    private val lowRamDevice: Boolean,
    private val emulator: Boolean,
) {

    fun scaleTimeout(
        base: Long,
        min: Long? = null,
        max: Long? = null,
        extraMultiplier: Double = 1.0,
        allowTightening: Boolean = true,
    ): Long {
        if (base <= 0L) {
            return base
        }
        var multiplier = if (extraMultiplier > 0.0) extraMultiplier else 1.0
        val totalGb = totalMemBytes?.toDouble()?.div(1024.0 * 1024.0 * 1024.0)
        val availableRatio = if (totalMemBytes != null && totalMemBytes > 0L && availMemBytes != null) {
            availMemBytes.toDouble() / totalMemBytes.toDouble()
        } else {
            null
        }

        if (lowMemory || lowRamDevice) {
            multiplier = max(multiplier, 2.5)
        }

        if (totalGb != null) {
            multiplier = when {
                totalGb < 3.5 -> max(multiplier, 2.5)
                totalGb < 4.0 -> max(multiplier, 2.25)
                totalGb < 6.0 -> max(multiplier, 1.6)
                totalGb > 8.0 && allowTightening -> min(multiplier, 0.75)
                else -> multiplier
            }
        }

        if (emulator) {
            multiplier = max(multiplier, 1.75)
        }

        if (availableRatio != null) {
            multiplier = when {
                availableRatio < 0.2 -> max(multiplier, 2.75)
                availableRatio < 0.25 -> max(multiplier, 2.25)
                availableRatio < 0.35 -> max(multiplier, 1.9)
                availableRatio > 0.6 && totalGb != null && totalGb >= 6.0 && allowTightening -> min(multiplier, 0.85)
                else -> multiplier
            }
        }

        if (!allowTightening) {
            multiplier = max(multiplier, 1.0)
        }

        val scaled = (base * multiplier).toLong()
        val bounded = when {
            min != null && scaled < min -> min
            max != null && scaled > max -> max
            else -> scaled
        }
        return if (bounded > 0L) bounded else base
    }

    companion object {
        fun forContext(context: Context): DeviceAdaptiveTimeouts {
            return runHarnessEntry("DeviceAdaptiveTimeouts", "forContext") {
                val appContext = context.applicationContext
                val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                var totalMem: Long? = null
                var availMem: Long? = null
                var lowMemory = false
                var lowRam = false
                if (activityManager != null) {
                    runCatching {
                        val memoryInfo = ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memoryInfo)
                        totalMem = memoryInfo.totalMem.takeIf { it > 0L }
                        availMem = memoryInfo.availMem.takeIf { it > 0L }
                        lowMemory = memoryInfo.lowMemory
                        lowRam = isLowRamDevice(activityManager)
                    }
                }
                val isEmulator = isEmulatorBuild()
                DeviceAdaptiveTimeouts(
                    totalMemBytes = totalMem,
                    availMemBytes = availMem,
                    lowMemory = lowMemory,
                    lowRamDevice = lowRam,
                    emulator = isEmulator,
                )
            }
        }

        @Suppress("DEPRECATION")
        private fun isLowRamDevice(activityManager: ActivityManager): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                activityManager.isLowRamDevice
            } else {
                false
            }
        }

        private fun isEmulatorBuild(): Boolean {
            val fingerprint = Build.FINGERPRINT?.lowercase() ?: ""
            val model = Build.MODEL?.lowercase() ?: ""
            val product = Build.PRODUCT?.lowercase() ?: ""
            val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
            return listOf(fingerprint, model, product, manufacturer).any { value ->
                value.contains("generic") ||
                    value.contains("emulator") ||
                    value.contains("sdk_gphone") ||
                    value.contains("sdk")
            }
        }
    }
}
