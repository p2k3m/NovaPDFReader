package com.novapdf.reader.cache

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.concurrent.TimeUnit

object PdfCacheRoot {
    private const val TAG = "PdfCacheRoot"

    const val ROOT_DIRECTORY = "pdf-cache"
    const val DOCUMENTS_DIRECTORY = "docs"
    const val THUMBNAILS_DIRECTORY = "thumbs"
    const val TILES_DIRECTORY = "tiles"
    const val INDEXES_DIRECTORY = "indexes"

    private val SUBDIRECTORIES = listOf(
        DOCUMENTS_DIRECTORY,
        THUMBNAILS_DIRECTORY,
        TILES_DIRECTORY,
        INDEXES_DIRECTORY,
    )

    private val MAX_DIRECTORY_BYTES = mapOf(
        DOCUMENTS_DIRECTORY to 256L * 1024L * 1024L,
        THUMBNAILS_DIRECTORY to 96L * 1024L * 1024L,
        TILES_DIRECTORY to 192L * 1024L * 1024L,
        INDEXES_DIRECTORY to 128L * 1024L * 1024L,
    )

    private val MAX_DIRECTORY_AGE_MS = mapOf(
        DOCUMENTS_DIRECTORY to TimeUnit.DAYS.toMillis(30),
        THUMBNAILS_DIRECTORY to TimeUnit.DAYS.toMillis(14),
        TILES_DIRECTORY to TimeUnit.DAYS.toMillis(14),
        INDEXES_DIRECTORY to TimeUnit.DAYS.toMillis(45),
    )

    fun root(context: Context): File = File(context.cacheDir, ROOT_DIRECTORY).also(::ensureDirectory)

    fun documents(context: Context): File = ensureSubDirectory(root(context), DOCUMENTS_DIRECTORY)

    fun thumbnails(context: Context): File = ensureSubDirectory(root(context), THUMBNAILS_DIRECTORY)

    fun tiles(context: Context): File = ensureSubDirectory(root(context), TILES_DIRECTORY)

    fun indexes(context: Context): File = ensureSubDirectory(root(context), INDEXES_DIRECTORY)

    fun ensureSubdirectories(context: Context) {
        val rootDir = root(context)
        SUBDIRECTORIES.forEach { ensureSubDirectory(rootDir, it) }
    }

    fun purge(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val rootDir = root(context)
        SUBDIRECTORIES.forEach { name ->
            val directory = File(rootDir, name)
            val budget = MAX_DIRECTORY_BYTES[name] ?: 0L
            val maxAge = MAX_DIRECTORY_AGE_MS[name] ?: 0L
            purgeDirectory(directory, budget, maxAge, nowMs)
        }
    }

    @VisibleForTesting
    internal fun purgeDirectory(
        directory: File,
        maxBytes: Long,
        maxAgeMs: Long,
        nowMs: Long,
    ) {
        CachePurger.purgeDirectory(directory, maxBytes, maxAgeMs, nowMs, TAG)
    }

    private fun ensureSubDirectory(root: File, name: String): File {
        val directory = File(root, name)
        ensureDirectory(directory)
        return directory
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                Log.w(TAG, "Cache path is not a directory: ${directory.absolutePath}")
            }
            return
        }
        if (!directory.mkdirs()) {
            Log.w(TAG, "Unable to create cache directory at ${directory.absolutePath}")
        }
    }
}

private object CachePurger {

    fun purgeDirectory(
        directory: File,
        maxBytes: Long,
        maxAgeMs: Long,
        nowMs: Long,
        tag: String,
    ) {
        if (!directory.exists() || !directory.isDirectory) {
            return
        }
        val files = directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.lastModified() }
            .toMutableList()
        if (files.isEmpty()) {
            pruneEmptyDirectories(directory, tag)
            return
        }
        var totalBytes = files.sumOf { it.length() }

        if (maxAgeMs > 0) {
            val iterator = files.iterator()
            while (iterator.hasNext()) {
                val file = iterator.next()
                val age = nowMs - file.lastModified()
                if (age > maxAgeMs) {
                    val size = file.length()
                    if (deleteFile(file, tag)) {
                        totalBytes -= size
                        iterator.remove()
                    }
                }
            }
        }

        if (maxBytes > 0 && totalBytes > maxBytes) {
            for (file in files) {
                if (totalBytes <= maxBytes) break
                if (!file.exists()) continue
                val size = file.length()
                if (deleteFile(file, tag)) {
                    totalBytes -= size
                }
            }
        }

        pruneEmptyDirectories(directory, tag)
    }

    private fun deleteFile(file: File, tag: String): Boolean {
        return if (file.delete()) {
            true
        } else {
            Log.w(tag, "Unable to delete cache entry at ${file.absolutePath}")
            false
        }
    }

    private fun pruneEmptyDirectories(root: File, tag: String) {
        root.walkBottomUp()
            .filter { it != root && it.isDirectory }
            .forEach { dir ->
                val children = dir.list()
                if (children == null) {
                    Log.w(tag, "Unable to list contents of ${dir.absolutePath}")
                    return@forEach
                }
                if (children.isEmpty() && !dir.delete()) {
                    Log.w(tag, "Unable to delete empty cache directory at ${dir.absolutePath}")
                }
            }
    }
}
