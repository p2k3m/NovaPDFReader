package com.novapdf.reader

import android.os.Debug
import android.os.Process
import android.util.Log
import com.novapdf.reader.logging.LogField
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field

internal object HarnessExceptionLogging {
    private const val TAG = "HarnessEntryPoint"

    fun log(component: String, operation: String, error: Throwable) {
        val payload = buildPayload(component, operation, error)
        val failureContext = HarnessFailureContext(
            testName = component,
            userAction = operation,
        )
        val fields = mutableListOf<LogField>()
        fields += field("component", component)
        fields += field("operation", operation)
        fields.addAll(
            HarnessFailureMetadata.buildFields(
                reason = error.message,
                context = failureContext,
                error = error,
            )
        )
        NovaLog.e(tag = TAG, message = payload, throwable = error, fields = fields.toTypedArray())
        Log.e(TAG, payload, error)
        println("$TAG: $payload\n${Log.getStackTraceString(error)}")
    }

    private fun buildPayload(component: String, operation: String, error: Throwable): String {
        val message = error.message ?: error::class.java.simpleName ?: "Unknown"
        val type = error::class.java.name
        return "{" +
            "\"event\":\"harness_exception\"," +
            "\"component\":\"${component.escapeForJson()}\"," +
            "\"operation\":\"${operation.escapeForJson()}\"," +
            "\"errorType\":\"${type.escapeForJson()}\"," +
            "\"message\":\"${message.escapeForJson()}\"" +
            "}"
    }
}

private object HarnessPhaseMetricsLogging {
    private const val TAG = "HarnessEntryPoint"

    fun log(
        component: String,
        operation: String,
        status: String,
        startTimestampMs: Long,
        endTimestampMs: Long,
        startCpuMs: Long?,
        endCpuMs: Long?,
        startPssKb: Int?,
        endPssKb: Int?,
        error: Throwable?,
    ) {
        val durationMs = (endTimestampMs - startTimestampMs).coerceAtLeast(0L)
        val cpuDeltaMs = if (startCpuMs != null && endCpuMs != null) endCpuMs - startCpuMs else null
        val pssDeltaKb = if (startPssKb != null && endPssKb != null) endPssKb - startPssKb else null

        val fields = mutableListOf<LogField>()
        fields += field("event", "harness_phase_metrics")
        fields += field("component", component)
        fields += field("operation", operation)
        fields += field("status", status)
        fields += field("startTimeMs", startTimestampMs)
        fields += field("endTimeMs", endTimestampMs)
        fields += field("durationMs", durationMs)
        startCpuMs?.let { fields += field("cpuStartMs", it) }
        endCpuMs?.let { fields += field("cpuEndMs", it) }
        cpuDeltaMs?.let { fields += field("cpuDeltaMs", it) }
        startPssKb?.let { fields += field("pssStartKb", it) }
        endPssKb?.let { fields += field("pssEndKb", it) }
        pssDeltaKb?.let { fields += field("pssDeltaKb", it) }
        if (error != null) {
            fields += field("errorType", error::class.java.name)
            fields += field("errorMessage", error.message ?: error::class.java.simpleName ?: "Unknown")
        }

        val payloadFields = mutableListOf<String>()
        payloadFields += "\"event\":\"harness_phase_metrics\""
        payloadFields += "\"component\":\"${component.escapeForJson()}\""
        payloadFields += "\"operation\":\"${operation.escapeForJson()}\""
        payloadFields += "\"status\":\"${status.escapeForJson()}\""
        payloadFields += "\"startTimeMs\":$startTimestampMs"
        payloadFields += "\"endTimeMs\":$endTimestampMs"
        payloadFields += "\"durationMs\":$durationMs"
        startCpuMs?.let { payloadFields += "\"cpuStartMs\":$it" }
        endCpuMs?.let { payloadFields += "\"cpuEndMs\":$it" }
        cpuDeltaMs?.let { payloadFields += "\"cpuDeltaMs\":$it" }
        startPssKb?.let { payloadFields += "\"pssStartKb\":$it" }
        endPssKb?.let { payloadFields += "\"pssEndKb\":$it" }
        pssDeltaKb?.let { payloadFields += "\"pssDeltaKb\":$it" }
        if (error != null) {
            val type = error::class.java.name
            val message = error.message ?: error::class.java.simpleName ?: "Unknown"
            payloadFields += "\"errorType\":\"${type.escapeForJson()}\""
            payloadFields += "\"errorMessage\":\"${message.escapeForJson()}\""
        }
        val payload = payloadFields.joinToString(prefix = "{", postfix = "}")

        val message = "Harness phase metrics [$component#$operation]"
        val fieldArray = fields.toTypedArray()
        if (error == null) {
            NovaLog.i(tag = TAG, message = message, fields = fieldArray)
            Log.i(TAG, payload)
        } else {
            NovaLog.w(tag = TAG, message = message, fields = fieldArray)
            Log.w(TAG, payload)
        }
        println("$TAG: $payload")
    }
}

private class HarnessPhaseMetricsSession(
    private val component: String,
    private val operation: String,
) {
    private val startTimestampMs: Long = System.currentTimeMillis()
    private val startCpuMs: Long? = sampleCpuTimeMs()
    private val startPssKb: Int? = samplePssKb()

    fun recordSuccess() {
        emit(status = "success", error = null)
    }

    fun recordFailure(error: Throwable) {
        emit(status = "error", error = error)
    }

    private fun emit(status: String, error: Throwable?) {
        val endTimestampMs = System.currentTimeMillis()
        val endCpuMs = sampleCpuTimeMs()
        val endPssKb = samplePssKb()
        HarnessPhaseMetricsLogging.log(
            component = component,
            operation = operation,
            status = status,
            startTimestampMs = startTimestampMs,
            endTimestampMs = endTimestampMs,
            startCpuMs = startCpuMs,
            endCpuMs = endCpuMs,
            startPssKb = startPssKb,
            endPssKb = endPssKb,
            error = error,
        )
    }

    private fun sampleCpuTimeMs(): Long? = runCatching { Process.getElapsedCpuTime() }.getOrNull()

    private fun samplePssKb(): Int? = runCatching {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        info.totalPss
    }.getOrNull()
}

internal inline fun <T> runHarnessEntry(
    component: String,
    operation: String,
    block: () -> T,
): T {
    val metricsSession = HarnessPhaseMetricsSession(component, operation)
    return try {
        block().also { metricsSession.recordSuccess() }
    } catch (error: Throwable) {
        metricsSession.recordFailure(error)
        HarnessExceptionLogging.log(component, operation, error)
        throw error
    }
}

internal suspend fun <T> runHarnessEntrySuspending(
    component: String,
    operation: String,
    block: suspend () -> T,
): T {
    val metricsSession = HarnessPhaseMetricsSession(component, operation)
    return try {
        block().also { metricsSession.recordSuccess() }
    } catch (error: Throwable) {
        metricsSession.recordFailure(error)
        HarnessExceptionLogging.log(component, operation, error)
        throw error
    }
}

private fun String.escapeForJson(): String {
    return buildString(length) {
        for (char in this@escapeForJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
