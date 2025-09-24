package com.novapdf.reader.data

import android.content.SharedPreferences
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
class BookmarkManagerTest {
    private lateinit var dao: FakeBookmarkDao
    private lateinit var preferences: FakeSharedPreferences

    @BeforeEach
    fun setUp() {
        dao = FakeBookmarkDao()
        preferences = FakeSharedPreferences()
    }

    @AfterEach
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun toggleBookmarkAddsAndRemovesEntries() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = BookmarkManager(
            bookmarkDao = dao,
            dispatcher = dispatcher
        )

        assertTrue(manager.toggleBookmark("doc", 1))
        assertEquals(listOf(1), manager.bookmarks("doc"))

        assertFalse(manager.toggleBookmark("doc", 1))
        assertTrue(manager.bookmarks("doc").isEmpty())
    }

    @Test
    fun migratesLegacyPreferencesIntoDatabase() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        preferences.edit()
            .putStringSet("bookmark_doc", setOf("1", "3", "invalid"))
            .apply()

        val manager = BookmarkManager(
            bookmarkDao = dao,
            legacyPreferences = preferences,
            dispatcher = dispatcher,
            timeProvider = { 42L }
        )

        assertEquals(listOf(1, 3), manager.bookmarks("doc"))
        val entityTimestamps = dao.bookmarksForDocument("doc").map { it.timestamp }
        assertTrue(entityTimestamps.all { it == 42L })
        assertNull(preferences.getStringSet("bookmark_doc", null))
    }

    @Test
    fun clearsLegacyPreferencesWhenDatabaseAlreadyPopulated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        dao.upsert(BookmarkEntity("doc", 5, 10L))
        preferences.edit().putStringSet("bookmark_doc", setOf("1", "2")).apply()

        val manager = BookmarkManager(
            bookmarkDao = dao,
            legacyPreferences = preferences,
            dispatcher = dispatcher
        )

        assertEquals(listOf(5), manager.bookmarks("doc"))
        assertNull(preferences.getStringSet("bookmark_doc", null))
    }

    private class FakeBookmarkDao : BookmarkDao {
        private val bookmarks = mutableMapOf<Pair<String, Int>, BookmarkEntity>()

        override suspend fun bookmarksForDocument(documentId: String): List<BookmarkEntity> {
            return bookmarks.values
                .filter { it.documentId == documentId }
                .sortedBy { it.pageIndex }
        }

        override suspend fun isBookmarked(documentId: String, pageIndex: Int): Boolean {
            return bookmarks.containsKey(documentId to pageIndex)
        }

        override suspend fun upsert(bookmark: BookmarkEntity) {
            bookmarks[bookmark.documentId to bookmark.pageIndex] = bookmark
        }

        override suspend fun remove(documentId: String, pageIndex: Int) {
            bookmarks.remove(documentId to pageIndex)
        }

        override suspend fun countAll(): Int = bookmarks.size
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? {
            return data[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            val value = data[key] as? Set<String> ?: return defValues
            return value.toMutableSet()
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return data[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return data[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return data[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return data[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // Not required for tests
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // Not required for tests
        }

        inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?
            ): SharedPreferences.Editor = apply {
                key?.let { pending[it] = values?.toSet() }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                key?.let { removals += it }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clear = true
            }

            override fun commit(): Boolean {
                applyChanges()
                return true
            }

            override fun apply() {
                applyChanges()
            }

            private fun applyChanges() {
                if (clear) {
                    data.clear()
                    clear = false
                }
                removals.forEach { data.remove(it) }
                pending.forEach { (key, value) ->
                    if (value == null) {
                        data.remove(key)
                    } else {
                        data[key] = value
                    }
                }
                pending.clear()
                removals.clear()
            }
        }
    }
}
