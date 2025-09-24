package com.novapdf.reader.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.junit5.RobolectricExtension

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(RobolectricExtension::class)
class BookmarkDaoTest {
    private lateinit var database: NovaPdfDatabase
    private lateinit var dao: BookmarkDao

    @BeforeEach
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NovaPdfDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.bookmarkDao()
    }

    @AfterEach
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndQueryBookmarks() = runTest {
        dao.upsert(BookmarkEntity("doc", 2, 100L))
        dao.upsert(BookmarkEntity("doc", 1, 200L))
        dao.upsert(BookmarkEntity("other", 5, 300L))

        val bookmarks = dao.bookmarksForDocument("doc")
        assertEquals(listOf(1, 2), bookmarks.map { it.pageIndex })
    }

    @Test
    fun isBookmarkedReflectsStoredEntries() = runTest {
        assertFalse(dao.isBookmarked("doc", 3))
        dao.upsert(BookmarkEntity("doc", 3, 100L))
        assertTrue(dao.isBookmarked("doc", 3))
    }

    @Test
    fun removeDeletesBookmark() = runTest {
        dao.upsert(BookmarkEntity("doc", 7, 100L))
        assertTrue(dao.isBookmarked("doc", 7))

        dao.remove("doc", 7)
        assertFalse(dao.isBookmarked("doc", 7))
    }

    @Test
    fun countAllReturnsTotalBookmarks() = runTest {
        assertEquals(0, dao.countAll())
        dao.upsert(BookmarkEntity("doc", 1, 100L))
        dao.upsert(BookmarkEntity("doc", 2, 200L))
        dao.upsert(BookmarkEntity("other", 5, 300L))

        assertEquals(3, dao.countAll())
    }
}
