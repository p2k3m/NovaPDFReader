package com.novapdf.reader

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException

internal fun writableStorageCandidates(context: Context): List<File> {
    val directories = buildList {
        context.cacheDir?.let(::add)
        context.filesDir?.let(::add)
        context.codeCacheDir?.let(::add)
        context.noBackupFilesDir?.let(::add)
        context.externalCacheDir?.let(::add)
        context.externalCacheDirs?.forEach { dir -> dir?.let(::add) }
        context.getExternalFilesDir(null)?.let(::add)
        context.getExternalFilesDirs(null)?.forEach { dir -> dir?.let(::add) }
        context.externalMediaDirs?.forEach { dir -> dir?.let(::add) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.dataDir?.let(::add)
        }
    }

    return directories
        .mapNotNull(::prepareWritableDirectory)
        .distinctBy { it.absolutePath }
}

private fun prepareWritableDirectory(directory: File?): File? {
    if (directory == null) return null

    return try {
        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }

        val probe = File(directory, ".storage-probe-${'$'}{System.nanoTime()}")
        try {
            probe.outputStream().use { /* Ensure we can create and close a file */ }
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
