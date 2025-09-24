package com.novapdf.reader.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BookmarkManager(
    private val bookmarkDao: BookmarkDao,
    private val legacyPreferences: SharedPreferences? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val migrationMutex = Mutex()

    @Volatile
    private var migrationComplete = false

    suspend fun toggleBookmark(documentId: String, pageIndex: Int): Boolean {
        ensureMigration()
        return withContext(dispatcher) {
            val exists = bookmarkDao.isBookmarked(documentId, pageIndex)
            if (exists) {
                bookmarkDao.remove(documentId, pageIndex)
                false
            } else {
                bookmarkDao.upsert(
                    BookmarkEntity(
                        documentId = documentId,
                        pageIndex = pageIndex,
                        timestamp = timeProvider()
                    )
                )
                true
            }
        }
    }

    suspend fun bookmarks(documentId: String): List<Int> {
        ensureMigration()
        return withContext(dispatcher) {
            bookmarkDao.bookmarksForDocument(documentId).map { it.pageIndex }
        }
    }

    private suspend fun ensureMigration() {
        if (migrationComplete) return
        migrationMutex.withLock {
            if (migrationComplete) return
            migrateLegacyBookmarks()
            migrationComplete = true
        }
    }

    private suspend fun migrateLegacyBookmarks() = withContext(dispatcher) {
        val preferences = legacyPreferences ?: return@withContext
        val entries = preferences.all.filterKeys { it.startsWith(LEGACY_KEY_PREFIX) }
        if (entries.isEmpty()) {
            clearMigrationFlag(preferences)
            return@withContext
        }
        val keysToRemove = mutableListOf<String>()
        val hasExistingBookmarks = bookmarkDao.countAll() > 0
        entries.forEach { (key, value) ->
            val documentId = key.removePrefix(LEGACY_KEY_PREFIX)
            val pages = (value as? Set<*>)
                ?.mapNotNull { (it as? String)?.toIntOrNull() }
                .orEmpty()
            if (pages.isEmpty() || hasExistingBookmarks) {
                keysToRemove += key
                return@forEach
            }
            pages.forEach { pageIndex ->
                bookmarkDao.upsert(
                    BookmarkEntity(
                        documentId = documentId,
                        pageIndex = pageIndex,
                        timestamp = timeProvider()
                    )
                )
            }
            keysToRemove += key
        }
        if (keysToRemove.isNotEmpty()) {
            val editor = preferences.edit()
            keysToRemove.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun clearMigrationFlag(preferences: SharedPreferences) {
        val keys = preferences.all.keys.filter { it.startsWith(LEGACY_KEY_PREFIX) }
        if (keys.isEmpty()) return
        val editor = preferences.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        const val LEGACY_PREFERENCES_NAME: String = "novapdf_bookmarks"
        private const val LEGACY_KEY_PREFIX = "bookmark_"
    }
}
