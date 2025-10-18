package com.novapdf.reader.logging

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
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
    private val logDirectory: File? = runCatching { resolveLogDirectory(appContext) }
        .onFailure { error ->
            Log.w(TAG, "Unable to resolve writable directory for crash logs; disabling persistence", error)
        }
        .getOrNull()
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US).withZone(ZoneOffset.UTC)
    private val installed = AtomicBoolean(false)
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val directoryWarningLogged = AtomicBoolean(false)

    override fun install() {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleFatalException(thread, throwable)
            } catch (reportError: Throwable) {
                Log.e(TAG, "Unable to record fatal exception", reportError)
            } finally {
                runCatching { defaultHandler?.uncaughtException(thread, throwable) }
                    .onFailure { delegateError ->
                        Log.e(TAG, "Delegating fatal exception failed", delegateError)
                    }
            }
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

    private fun handleFatalException(thread: Thread, throwable: Throwable) {
        val message = "Uncaught exception on thread ${thread.name}"
        val logFields = buildList {
            add(field("thread", thread.name))
            add(field("id", thread.id))
            add(field("daemon", thread.isDaemon))
            add(field("priority", thread.priority))
            thread.threadGroup?.name?.let { add(field("threadGroup", it)) }
        }.toTypedArray()

        Log.e(TAG, message, throwable)
        NovaLog.e(TAG, message, throwable, logFields)

        val metadata = buildMap {
            put("thread", thread.name)
            put("id", thread.id.toString())
            put("daemon", thread.isDaemon.toString())
            put("priority", thread.priority.toString())
            thread.threadGroup?.name?.let { put("threadGroup", it) }
        }

        persist("fatal", throwable.stackTraceToString(), metadata, immediate = true)
    }

    private fun persist(
        kind: String,
        body: String,
        metadata: Map<String, String> = emptyMap(),
        immediate: Boolean = false,
    ) {
        val directory = logDirectory
        if (directory == null) {
            if (directoryWarningLogged.compareAndSet(false, true)) {
                Log.w(TAG, "Crash reporter persistence disabled; dropping $kind event")
            }
            return
        }

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
        val persistAction = {
            runCatching {
                writeLog(File(directory, fileName), entry)
                trimLogs()
            }.onFailure { error ->
                Log.w(TAG, "Unable to persist crash log", error)
            }
        }

        if (immediate) {
            persistAction()
        } else {
            applicationScope.launch { persistAction() }
        }
    }

    @VisibleForTesting
    fun crashLogDirectory(): File? = logDirectory

    private fun writeLog(file: File, entry: String) {
        FileWriter(file, false).use { writer ->
            writer.write(entry)
            writer.flush()
        }
    }

    private fun trimLogs() {
        val directory = logDirectory ?: return
        val logs = directory.listFiles { candidate -> candidate.extension.equals("log", true) }
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
                    runCatching { applicationContext.createDeviceProtectedStorageContext() }
                        .getOrNull()
                        ?.let(::add)
                }
            }.filterNotNull()

            val candidateParents = LinkedHashSet<File>()
            candidateContexts.forEach { candidateContext ->
                safeDirectoriesForContext(candidateContext).forEach { dir ->
                    candidateParents += dir
                }
            }

            val writableParent = candidateParents
                .mapNotNull(::prepareWritableDirectory)
                .firstOrNull()

            val resolvedParent = writableParent ?: candidateContexts
                .asSequence()
                .mapNotNull { ctx -> safeDirectoriesForContext(ctx).firstOrNull() }
                .firstOrNull()
                ?: throw IllegalStateException("Unable to resolve writable directory for crash logs")

            return File(resolvedParent, "crashlogs").apply { mkdirs() }
        }

        private fun safeDirectoriesForContext(context: Context): List<File> {
            return buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    addIfPresent { context.dataDir }
                }
                addIfPresent { context.filesDir }
                addIfPresent { context.cacheDir }
                addIfPresent { context.codeCacheDir }
                addIfPresent { context.noBackupFilesDir }
            }
        }

        private inline fun MutableList<File>.addIfPresent(block: () -> File?) {
            runCatching { block() }
                .getOrNull()
                ?.let { add(it) }
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
