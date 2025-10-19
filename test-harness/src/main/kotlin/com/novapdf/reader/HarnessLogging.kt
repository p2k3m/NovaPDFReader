package com.novapdf.reader

import android.os.Debug
import android.os.Process
import android.util.Log
import java.util.LinkedHashMap

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

internal object HarnessPhaseMetricsLogging {
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

internal object HarnessPhaseLifecycleLogging {
    private const val TAG = "HarnessPhase"
    private const val PREFIX = "HARNESS PHASE: "

    fun logStart(
        component: String,
        operation: String,
        attempt: Int,
        context: Map<String, String>,
    ) {
        emit(
            type = "start",
            component = component,
            operation = operation,
            attempt = attempt,
            context = context,
        )
    }

    fun logCheckpoint(
        component: String,
        operation: String,
        attempt: Int,
        checkpoint: String,
        detail: String?,
        context: Map<String, String>,
    ) {
        emit(
            type = "checkpoint",
            component = component,
            operation = operation,
            attempt = attempt,
            checkpoint = checkpoint,
            detail = detail,
            context = context,
        )
    }

    fun logMessage(
        component: String,
        operation: String,
        attempt: Int,
        detail: String,
        context: Map<String, String>,
    ) {
        emit(
            type = "log",
            component = component,
            operation = operation,
            attempt = attempt,
            detail = detail,
            context = context,
        )
    }

    fun logAbort(
        component: String,
        operation: String,
        attempt: Int,
        context: Map<String, String>,
        error: Throwable,
    ) {
        emit(
            type = "abort",
            component = component,
            operation = operation,
            attempt = attempt,
            context = context,
            error = error,
        )
    }

    fun logRetry(
        component: String,
        operation: String,
        attempt: Int,
        nextAttempt: Int,
        context: Map<String, String>,
        error: Throwable,
    ) {
        emit(
            type = "retry",
            component = component,
            operation = operation,
            attempt = attempt,
            nextAttempt = nextAttempt,
            context = context,
            error = error,
        )
    }

    fun logComplete(
        component: String,
        operation: String,
        attempt: Int,
        context: Map<String, String>,
    ) {
        emit(
            type = "complete",
            component = component,
            operation = operation,
            attempt = attempt,
            context = context,
        )
    }

    private fun emit(
        type: String,
        component: String,
        operation: String,
        attempt: Int,
        context: Map<String, String>,
        checkpoint: String? = null,
        detail: String? = null,
        nextAttempt: Int? = null,
        error: Throwable? = null,
    ) {
        val fields = mutableListOf<String>()
        fields += "\"event\":\"harness_phase\""
        fields += "\"type\":\"${type.escapeForJson()}\""
        fields += "\"component\":\"${component.escapeForJson()}\""
        fields += "\"operation\":\"${operation.escapeForJson()}\""
        fields += "\"attempt\":$attempt"
        fields += "\"timestampMs\":${System.currentTimeMillis()}"
        fields += "\"context\":${context.toJsonObject()}"
        checkpoint?.let { fields += "\"checkpoint\":\"${it.escapeForJson()}\"" }
        detail?.let { fields += "\"detail\":\"${it.escapeForJson()}\"" }
        nextAttempt?.let { fields += "\"nextAttempt\":$it" }
        if (error != null) {
            val typeName = error::class.java.name
            val message = error.message ?: error::class.java.simpleName ?: "Unknown"
            fields += "\"errorType\":\"${typeName.escapeForJson()}\""
            fields += "\"errorMessage\":\"${message.escapeForJson()}\""
        }
        val payload = fields.joinToString(prefix = "{", postfix = "}")
        val message = "$PREFIX$payload"
        println(message)
        Log.i(TAG, message)
    }
}

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

internal class HarnessPhaseScope internal constructor(
    private val component: String,
    private val operation: String,
    val attempt: Int,
    initialContext: Map<String, String>,
) {
    private val context: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        for ((key, value) in initialContext) {
            put(key, value)
        }
    }

    fun annotate(key: String, value: Any?) {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return
        val normalizedValue = value?.toString()?.trim()
        if (normalizedValue.isNullOrEmpty()) return
        context[normalizedKey] = normalizedValue
    }

    fun checkpoint(name: String, detail: String? = null) {
        val normalized = name.trim()
        if (normalized.isEmpty()) return
        HarnessPhaseLifecycleLogging.logCheckpoint(
            component = component,
            operation = operation,
            attempt = attempt,
            checkpoint = normalized,
            detail = detail?.takeIf { it.isNotBlank() }?.trim(),
            context = contextSnapshot(),
        )
    }

    fun log(detail: String) {
        val normalized = detail.trim()
        if (normalized.isEmpty()) return
        HarnessPhaseLifecycleLogging.logMessage(
            component = component,
            operation = operation,
            attempt = attempt,
            detail = normalized,
            context = contextSnapshot(),
        )
    }

    internal fun contextSnapshot(): Map<String, String> = LinkedHashMap(context)
}

internal inline fun <T> runHarnessOperation(
    component: String,
    operation: String,
    crossinline block: () -> T,
): T {
    return runHarnessOperationWithScope(
        component = component,
        operation = operation,
        maxAttempts = 1,
        baseContext = emptyMap(),
    ) {
        block()
    }
}

internal inline fun <T> runHarnessOperationWithRetries(
    component: String,
    operation: String,
    maxAttempts: Int,
    baseContext: Map<String, String> = emptyMap(),
    crossinline block: HarnessPhaseScope.() -> T,
): T {
    return runHarnessOperationWithScope(
        component = component,
        operation = operation,
        maxAttempts = maxAttempts,
        baseContext = baseContext,
        block = block,
    )
}

internal inline fun <T> runHarnessOperationWithScope(
    component: String,
    operation: String,
    maxAttempts: Int = 1,
    baseContext: Map<String, String> = emptyMap(),
    crossinline block: HarnessPhaseScope.() -> T,
): T {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }

    val persistentContext = LinkedHashMap<String, String>().apply {
        for ((key, value) in baseContext) {
            put(key, value)
        }
    }

    var attempt = 1
    while (true) {
        val scope = HarnessPhaseScope(component, operation, attempt, persistentContext)
        val metricsSession = HarnessPhaseMetricsSession(component, operation)
        HarnessPhaseLifecycleLogging.logStart(
            component = component,
            operation = operation,
            attempt = attempt,
            context = scope.contextSnapshot(),
        )
        try {
            val result = scope.block()
            metricsSession.recordSuccess()
            val snapshot = scope.contextSnapshot()
            persistentContext.putAll(snapshot)
            HarnessPhaseLifecycleLogging.logComplete(
                component = component,
                operation = operation,
                attempt = attempt,
                context = snapshot,
            )
            return result
        } catch (error: Throwable) {
            metricsSession.recordFailure(error)
            val snapshot = scope.contextSnapshot()
            persistentContext.putAll(snapshot)
            HarnessPhaseLifecycleLogging.logAbort(
                component = component,
                operation = operation,
                attempt = attempt,
                context = snapshot,
                error = error,
            )
            HarnessLogging.log(component, operation, error)
            if (attempt >= maxAttempts) {
                throw error
            }
            val nextAttempt = attempt + 1
            HarnessPhaseLifecycleLogging.logRetry(
                component = component,
                operation = operation,
                attempt = attempt,
                nextAttempt = nextAttempt,
                context = snapshot,
                error = error,
            )
            attempt = nextAttempt
        }
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

private fun Map<String, String>.toJsonObject(): String {
    if (isEmpty()) return "{}"
    return entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        val sanitizedKey = key.escapeForJson()
        val sanitizedValue = value.escapeForJson()
        "\"$sanitizedKey\":\"$sanitizedValue\""
    }
}
