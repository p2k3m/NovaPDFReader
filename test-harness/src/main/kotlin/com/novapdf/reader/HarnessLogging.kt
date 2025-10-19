package com.novapdf.reader

import android.os.Debug
import android.os.Process

internal object HarnessLogging {
    private const val TAG = "HarnessEntryPoint"

    fun log(component: String, operation: String, error: Throwable) {
        val payload = buildPayload(component, operation, error)
        System.err.println("$TAG: $payload")
        error.printStackTrace()
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

        val fields = mutableListOf<String>()
        fields += "\"event\":\"harness_phase_metrics\""
        fields += "\"component\":\"${component.escapeForJson()}\""
        fields += "\"operation\":\"${operation.escapeForJson()}\""
        fields += "\"status\":\"${status.escapeForJson()}\""
        fields += "\"startTimeMs\":$startTimestampMs"
        fields += "\"endTimeMs\":$endTimestampMs"
        fields += "\"durationMs\":$durationMs"
        startCpuMs?.let { fields += "\"cpuStartMs\":$it" }
        endCpuMs?.let { fields += "\"cpuEndMs\":$it" }
        cpuDeltaMs?.let { fields += "\"cpuDeltaMs\":$it" }
        startPssKb?.let { fields += "\"pssStartKb\":$it" }
        endPssKb?.let { fields += "\"pssEndKb\":$it" }
        pssDeltaKb?.let { fields += "\"pssDeltaKb\":$it" }
        if (error != null) {
            val type = error::class.java.name
            val message = error.message ?: error::class.java.simpleName ?: "Unknown"
            fields += "\"errorType\":\"${type.escapeForJson()}\""
            fields += "\"errorMessage\":\"${message.escapeForJson()}\""
        }

        val payload = fields.joinToString(prefix = "{", postfix = "}")
        System.out.println("$TAG: $payload")
    }
}

@PublishedApi
internal class HarnessPhaseMetricsSession(
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

internal inline fun <T> runHarnessOperation(
    component: String,
    operation: String,
    block: () -> T,
): T {
    val metricsSession = HarnessPhaseMetricsSession(component, operation)
    return try {
        block().also { metricsSession.recordSuccess() }
    } catch (error: Throwable) {
        metricsSession.recordFailure(error)
        HarnessLogging.log(component, operation, error)
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
