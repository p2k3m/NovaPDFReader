package com.novapdf.reader.logging

import androidx.annotation.VisibleForTesting
import timber.log.Timber

/**
 * Lightweight logging facade that routes events through a shared [Timber] tree while allowing
 * structured key/value pairs to be attached to each message.
 */
object NovaLog {

    fun install(debug: Boolean, crashReporter: CrashReporter) {
        synchronized(this) {
            Timber.uprootAll()
            if (debug) {
                Timber.plant(StructuredDebugTree())
            } else {
                Timber.plant(CrashReportingTree(crashReporter))
            }
        }
    }

    fun d(tag: String, message: String, vararg fields: LogField) {
        log(LogLevel.DEBUG, tag, null, message, fields)
    }

    fun d(tag: String, message: String, throwable: Throwable, vararg fields: LogField) {
        log(LogLevel.DEBUG, tag, throwable, message, fields)
    }

    fun i(tag: String, message: String, vararg fields: LogField) {
        log(LogLevel.INFO, tag, null, message, fields)
    }

    fun i(tag: String, message: String, throwable: Throwable, vararg fields: LogField) {
        log(LogLevel.INFO, tag, throwable, message, fields)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null, vararg fields: LogField) {
        log(LogLevel.WARN, tag, throwable, message, fields)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null, vararg fields: LogField) {
        log(LogLevel.ERROR, tag, throwable, message, fields)
    }

    @VisibleForTesting
    internal fun formatMessage(message: String, fields: Array<out LogField>): String {
        if (fields.isEmpty()) return message
        return buildString(message.length + 16 * fields.size) {
            append(message)
            fields.forEach { field ->
                append(" | ")
                append(field.render())
            }
        }
    }

    private fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable?,
        message: String,
        fields: Array<out LogField>,
    ) {
        val formatted = formatMessage(message, fields)
        val tagged = Timber.tag(tag)
        when {
            throwable != null -> when (level) {
                LogLevel.DEBUG -> tagged.d(throwable, formatted)
                LogLevel.INFO -> tagged.i(throwable, formatted)
                LogLevel.WARN -> tagged.w(throwable, formatted)
                LogLevel.ERROR -> tagged.e(throwable, formatted)
            }

            else -> when (level) {
                LogLevel.DEBUG -> tagged.d(formatted)
                LogLevel.INFO -> tagged.i(formatted)
                LogLevel.WARN -> tagged.w(formatted)
                LogLevel.ERROR -> tagged.e(formatted)
            }
        }
    }

    private enum class LogLevel { DEBUG, INFO, WARN, ERROR }
}
