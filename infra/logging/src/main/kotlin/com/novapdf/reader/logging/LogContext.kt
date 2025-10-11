package com.novapdf.reader.logging

/**
 * Describes contextual metadata that should accompany structured log statements.
 */
data class LogContext(
    val module: String,
    val operation: String,
    val documentId: String? = null,
    val pageIndex: Int? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
) {
    /**
     * Builds a [LogField] array combining the context with any additional [extras].
     */
    fun fields(vararg extras: LogField): Array<LogField> {
        val fields = ArrayList<LogField>(6 + extras.size)
        fields.add(field("module", module))
        fields.add(field("operation", operation))
        fields.add(field("docId", documentId))
        fields.add(field("pageIndex", pageIndex))
        fields.add(field("sizeBytes", sizeBytes))
        fields.add(field("durationMs", durationMs))
        fields.addAll(extras)
        return fields.toTypedArray()
    }
}
