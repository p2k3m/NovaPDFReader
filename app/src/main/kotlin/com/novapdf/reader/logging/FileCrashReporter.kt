package com.novapdf.reader.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Lightweight crash logger that persists fatal and non-fatal exceptions to the app's private
 * storage. This gives QA engineers actionable diagnostics on devices where third-party SDKs (such
 * as Crashlytics) are not yet configured.
 */
class FileCrashReporter(
    context: Context,
    private val maxLogFiles: Int = 20
) : CrashReporter {

    private val applicationScope: CoroutineScope = CoroutineScope(Job()) + Dispatchers.IO
    private val appContext = context.applicationContext
    private val logDirectory: File = resolveLogDirectory(appContext)
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US).withZone(ZoneOffset.UTC)
    private val installed = AtomicBoolean(false)
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun install() {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            persist("fatal", throwable.stackTraceToString(), mapOf("thread" to thread.name))
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) {
        val mergedMetadata = metadata.toMutableMap().apply {
            put("type", throwable::class.java.name)
        }
        persist("nonfatal", throwable.stackTraceToString(), mergedMetadata)
    }

    override fun logBreadcrumb(message: String) {
        persist("breadcrumb", message)
    }

    private fun persist(kind: String, body: String, metadata: Map<String, String> = emptyMap()) {
        val timestamp = timestampFormatter.format(Instant.now())
        val fileName = "$timestamp-$kind.log"
        val entry = buildString {
            appendLine("timestamp=$timestamp")
            appendLine("kind=$kind")
            metadata.forEach { (key, value) ->
                appendLine("$key=${value.replace('\n', ' ')}")
            }
            appendLine()
            append(body)
            if (!body.endsWith('\n')) {
                append('\n')
            }
        }
        applicationScope.launch {
            runCatching {
                writeLog(File(logDirectory, fileName), entry)
                trimLogs()
            }.onFailure { error ->
                Log.w(TAG, "Unable to persist crash log", error)
            }
        }
    }

    private fun writeLog(file: File, entry: String) {
        FileWriter(file, false).use { writer ->
            writer.write(entry)
            writer.flush()
        }
    }

    private fun trimLogs() {
        val logs = logDirectory.listFiles { candidate -> candidate.extension.equals("log", true) }
            ?.sortedByDescending(File::lastModified)
            ?: return
        if (logs.size <= maxLogFiles) return
        logs.drop(maxLogFiles).forEach { candidate ->
            runCatching { candidate.delete() }
        }
    }

    companion object {
        private const val TAG = "FileCrashReporter"

        private fun resolveLogDirectory(context: Context): File {
            val candidateParents = listOfNotNull(
                context.filesDir,
                context.cacheDir,
                context.codeCacheDir,
                context.noBackupFilesDir
            )
            val parent = candidateParents.firstOrNull { parent ->
                runCatching {
                    if (!parent.exists() && !parent.mkdirs()) {
                        return@runCatching false
                    }
                    // Probe that we can actually create files within the directory.
                    val probe = File(parent, ".crashlog-probe-${'$'}{System.nanoTime()}")
                    try {
                        probe.outputStream().use { }
                        true
                    } finally {
                        if (probe.exists() && !probe.delete()) {
                            // Ignore cleanup failures; this is best-effort.
                        }
                    }
                }.getOrElse { false }
            }

            requireNotNull(parent) {
                "Unable to resolve writable directory for crash logs"
            }

            return File(parent, "crashlogs").apply { mkdirs() }
        }
    }
}
