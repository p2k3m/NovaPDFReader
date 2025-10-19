package com.novapdf.reader

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.logging.LogField
import com.novapdf.reader.logging.field

internal data class HarnessFailureContext(
    val testName: String? = null,
    val documentId: String? = null,
    val pageIndex: Int? = null,
    val pageCount: Int? = null,
    val userAction: String? = null,
)

internal object HarnessFailureMetadata {

    fun buildFields(
        reason: String?,
        context: HarnessFailureContext,
        error: Throwable,
        includeDeviceStats: Boolean = true,
    ): Array<LogField> {
        val fields = mutableListOf<LogField>()
        fields += field("event", "harness_failure")
        fields += field("reason", reason ?: error.message ?: error::class.java.simpleName ?: "Unknown")
        fields += field("errorType", error::class.java.name)
        fields += field("errorMessage", error.message ?: error::class.java.simpleName ?: "Unknown")
        fields += field("threadName", Thread.currentThread().name)
        fields += field("threadId", Thread.currentThread().id)
        fields += field("threadState", Thread.currentThread().state.name)
        val threadPriority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
        fields += field("threadPriority", threadPriority)
        fields += field("contextTestName", context.testName)
        fields += field("contextUserAction", context.userAction)
        fields += field("contextDocumentId", context.documentId)
        fields += field("contextPageIndex", context.pageIndex)
        fields += field("contextPageNumber", context.pageIndex?.let { it + 1 })
        fields += field("contextPageCount", context.pageCount)
        if (includeDeviceStats) {
            fields.addAll(DeviceStats.fields())
        }
        return fields.toTypedArray()
    }

    private object DeviceStats {
        private val cachedFields: List<LogField> by lazy { collectDeviceFields() }

        fun fields(): List<LogField> = cachedFields

        private fun collectDeviceFields(): List<LogField> {
            val fields = mutableListOf<LogField>()
            fields += field("deviceApi", Build.VERSION.SDK_INT)
            fields += field("deviceModel", Build.MODEL ?: "unknown")
            fields += field("deviceManufacturer", Build.MANUFACTURER ?: "unknown")
            fields += field("deviceProduct", Build.PRODUCT ?: "unknown")
            val context = runCatching {
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            }.getOrNull()
            val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                runCatching {
                    val info = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(info)
                    fields += field("deviceTotalMemBytes", info.totalMem.takeIf { it > 0L })
                    fields += field("deviceAvailMemBytes", info.availMem.takeIf { it > 0L })
                    fields += field("deviceLowMemory", info.lowMemory)
                    fields += field("deviceLowRam", isLowRamDevice(activityManager))
                }
            }
            fields += field("deviceEmulator", isEmulatorBuild())
            return fields
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
