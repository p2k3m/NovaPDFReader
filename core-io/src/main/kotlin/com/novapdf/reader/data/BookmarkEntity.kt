package com.novapdf.reader.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "bookmarks", primaryKeys = ["document_id", "page_index"])
data class BookmarkEntity(
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long
)
