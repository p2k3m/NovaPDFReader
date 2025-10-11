package com.novapdf.reader.logging

/** Represents a structured key/value pair attached to a log statement. */
data class LogField(
    val key: String,
    val value: Any?,
) {
    fun render(): String {
        val renderedValue = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> "\"${sanitize(value.toString())}\""
        }
        return "$key=$renderedValue"
    }

    private fun sanitize(raw: String): String = buildString(raw.length) {
        raw.forEach { char ->
            when (char) {
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '"' -> append("\\\"")
                else -> append(char)
            }
        }
    }
}

/** Convenience factory for building [LogField] instances inline. */
fun field(key: String, value: Any?): LogField = LogField(key, value)
