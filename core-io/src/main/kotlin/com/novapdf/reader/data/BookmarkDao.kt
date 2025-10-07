package com.novapdf.reader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BookmarkDao {
    @Query(
        "SELECT * FROM bookmarks WHERE document_id = :documentId ORDER BY page_index ASC"
    )
    suspend fun bookmarksForDocument(documentId: String): List<BookmarkEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM bookmarks WHERE document_id = :documentId AND page_index = :pageIndex)"
    )
    suspend fun isBookmarked(documentId: String, pageIndex: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE document_id = :documentId AND page_index = :pageIndex")
    suspend fun remove(documentId: String, pageIndex: Int)

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun countAll(): Int

    @Query("SELECT DISTINCT document_id FROM bookmarks")
    suspend fun distinctDocumentIds(): List<String>
}
