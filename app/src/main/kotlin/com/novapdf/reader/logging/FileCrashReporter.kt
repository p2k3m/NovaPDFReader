package com.novapdf.reader.logging

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
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
            val applicationContext = context.applicationContext
            val candidateContexts = buildList {
                add(applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    add(applicationContext.createDeviceProtectedStorageContext())
                }
            }.filterNotNull()

            val candidateParents = LinkedHashSet<File>()
            candidateContexts.forEach { candidateContext ->
                listOfNotNull(
                    candidateContext.filesDir,
                    candidateContext.cacheDir,
                    candidateContext.codeCacheDir,
                    candidateContext.noBackupFilesDir,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        candidateContext.dataDir
                    } else {
                        null
                    }
                ).forEach { dir -> candidateParents += dir }
            }

            val writableParent = candidateParents
                .mapNotNull(::prepareWritableDirectory)
                .firstOrNull()

            val resolvedParent = writableParent ?: candidateContexts
                .asSequence()
                .mapNotNull { ctx ->
                    listOfNotNull(
                        ctx.cacheDir,
                        ctx.filesDir,
                        ctx.codeCacheDir,
                        ctx.noBackupFilesDir
                    ).firstOrNull()
                }
                .firstOrNull()
                ?: throw IllegalStateException("Unable to resolve writable directory for crash logs")

            return File(resolvedParent, "crashlogs").apply { mkdirs() }
        }
    }
}

private fun prepareWritableDirectory(directory: File?): File? {
    if (directory == null) return null

    return try {
        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }

        val probe = File(directory, ".crashlog-probe-${'$'}{System.nanoTime()}")
        try {
            probe.outputStream().use { /* Ensure directory is writable */ }
            directory
        } catch (error: IOException) {
            null
        } catch (error: SecurityException) {
            null
        } finally {
            if (probe.exists() && !probe.delete()) {
                // Ignore failure to delete the probe file; it is harmless temporary state.
            }
        }
    } catch (error: SecurityException) {
        null
    }
}
