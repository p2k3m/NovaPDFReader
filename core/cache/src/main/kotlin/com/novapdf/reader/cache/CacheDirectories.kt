package com.novapdf.reader.cache

import android.content.Context
import com.novapdf.reader.logging.NovaLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Provides strongly-typed accessors for the on-device cache hierarchy used by the reader.
 * Consumers should request this interface from DI rather than touching directory structure
 * constants directly so unit tests can substitute fake implementations when needed.
 */
interface CacheDirectories {
    fun root(): File
    fun documents(): File
    fun thumbnails(): File
    fun tiles(): File
    fun indexes(): File
    fun ensureSubdirectories()
    fun purge(nowMs: Long = System.currentTimeMillis())
}

class DefaultCacheDirectories(
    context: Context,
) : CacheDirectories {

    private val appContext = context.applicationContext

    override fun root(): File = File(appContext.cacheDir, ROOT_DIRECTORY).also(::ensureDirectory)

    override fun documents(): File = ensureSubDirectory(root(), DOCUMENTS_DIRECTORY)

    override fun thumbnails(): File = ensureSubDirectory(root(), THUMBNAILS_DIRECTORY)

    override fun tiles(): File = ensureSubDirectory(root(), TILES_DIRECTORY)

    override fun indexes(): File = ensureSubDirectory(root(), INDEXES_DIRECTORY)

    override fun ensureSubdirectories() {
        val rootDir = root()
        SUBDIRECTORIES.forEach { ensureSubDirectory(rootDir, it) }
    }

    override fun purge(nowMs: Long) {
        val rootDir = root()
        SUBDIRECTORIES.forEach { name ->
            val directory = File(rootDir, name)
            val budget = MAX_DIRECTORY_BYTES[name] ?: 0L
            val maxAge = MAX_DIRECTORY_AGE_MS[name] ?: 0L
            CachePurger.purgeDirectory(directory, budget, maxAge, nowMs, TAG)
        }
    }

    private fun ensureSubDirectory(root: File, name: String): File {
        val directory = File(root, name)
        ensureDirectory(directory)
        return directory
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                NovaLog.w(TAG, "Cache path is not a directory: ${directory.absolutePath}")
            }
            return
        }
        if (!directory.mkdirs()) {
            NovaLog.w(TAG, "Unable to create cache directory at ${directory.absolutePath}")
        }
    }

    private companion object {
        private const val TAG = "CacheDirectories"

        private const val ROOT_DIRECTORY = "pdf-cache"
        private const val DOCUMENTS_DIRECTORY = "docs"
        private const val THUMBNAILS_DIRECTORY = "thumbs"
        private const val TILES_DIRECTORY = "tiles"
        private const val INDEXES_DIRECTORY = "indexes"

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
    }
}

internal object CachePurger {

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
            NovaLog.w(tag, "Unable to delete cache entry at ${file.absolutePath}")
            false
        }
    }

    private fun pruneEmptyDirectories(root: File, tag: String) {
        root.walkBottomUp()
            .filter { it != root && it.isDirectory }
            .forEach { dir ->
                val children = dir.list()
                if (children == null) {
                    NovaLog.w(tag, "Unable to list contents of ${dir.absolutePath}")
                    return@forEach
                }
                if (children.isEmpty()) {
                    if (!dir.delete()) {
                        NovaLog.w(tag, "Unable to delete empty cache directory at ${dir.absolutePath}")
                    }
                }
            }
    }
}
