package com.novapdf.reader

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

internal inline fun <T> runHarnessOperation(
    component: String,
    operation: String,
    block: () -> T,
): T {
    return try {
        block()
    } catch (error: Throwable) {
        HarnessLogging.log(component, operation, error)
        throw error
    }
}
