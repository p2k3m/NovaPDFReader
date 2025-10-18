package com.novapdf.reader

import android.util.Log
import com.novapdf.reader.logging.NovaLog

internal object HarnessExceptionLogging {
    private const val TAG = "HarnessEntryPoint"

    fun log(component: String, operation: String, error: Throwable) {
        val payload = buildPayload(component, operation, error)
        NovaLog.e(tag = TAG, message = payload, throwable = error)
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
}

internal inline fun <T> runHarnessEntry(
    component: String,
    operation: String,
    block: () -> T,
): T {
    return try {
        block()
    } catch (error: Throwable) {
        HarnessExceptionLogging.log(component, operation, error)
        throw error
    }
}

internal suspend fun <T> runHarnessEntrySuspending(
    component: String,
    operation: String,
    block: suspend () -> T,
): T {
    return try {
        block()
    } catch (error: Throwable) {
        HarnessExceptionLogging.log(component, operation, error)
        throw error
    }
}
