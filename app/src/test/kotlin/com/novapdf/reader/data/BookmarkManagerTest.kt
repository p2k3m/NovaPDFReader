package com.novapdf.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookmarkManagerTest {
    private lateinit var context: Context
    private lateinit var database: NovaPdfDatabase
    private lateinit var dao: BookmarkDao
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovaPdfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.bookmarkDao()
        preferences = context.getSharedPreferences("bookmark_manager_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
        database.close()
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
}
