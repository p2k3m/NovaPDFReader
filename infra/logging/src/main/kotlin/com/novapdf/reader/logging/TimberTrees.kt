package com.novapdf.reader.logging

import android.util.Log
import timber.log.Timber

internal class StructuredDebugTree : Timber.DebugTree() {
    override fun createStackElementTag(element: StackTraceElement): String {
        val tag = super.createStackElementTag(element)
        return tag?.take(MAX_TAG_LENGTH) ?: DEFAULT_TAG
    }

    companion object {
        private const val MAX_TAG_LENGTH = 23
        private const val DEFAULT_TAG = "NovaPdf"
    }
}

internal class CrashReportingTree(
    private val crashReporter: CrashReporter,
) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag?.take(MAX_TAG_LENGTH) ?: DEFAULT_TAG
        Log.println(priority, safeTag, message)
        when {
            priority >= Log.ERROR && t != null -> {
                crashReporter.recordNonFatal(t, mapOf("tag" to safeTag, "message" to message))
            }

            priority >= Log.ERROR && t == null -> {
                crashReporter.recordNonFatal(RuntimeException(message), mapOf("tag" to safeTag))
            }
        }

        if (priority >= Log.WARN) {
            crashReporter.logBreadcrumb("$safeTag: $message")
        }
    }

    companion object {
        private const val MAX_TAG_LENGTH = 23
        private const val DEFAULT_TAG = "NovaPdf"
    }
}
