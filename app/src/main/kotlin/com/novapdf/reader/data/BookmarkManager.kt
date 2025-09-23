package com.novapdf.reader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BookmarkManager(context: Context) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("novapdf_bookmarks", Context.MODE_PRIVATE)

    suspend fun toggleBookmark(documentId: String, pageIndex: Int): Boolean = withContext(Dispatchers.IO) {
        val key = key(documentId)
        val entries = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        val value = pageIndex.toString()
        val isBookmarked = if (entries.contains(value)) {
            entries.remove(value)
            false
        } else {
            entries.add(value)
            true
        }
        preferences.edit().putStringSet(key, entries).apply()
        isBookmarked
    }

    suspend fun bookmarks(documentId: String): List<Int> = withContext(Dispatchers.IO) {
        preferences.getStringSet(key(documentId), emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.sorted()
            .orEmpty()
    }

    private fun key(documentId: String): String = "bookmark_$documentId"
}
