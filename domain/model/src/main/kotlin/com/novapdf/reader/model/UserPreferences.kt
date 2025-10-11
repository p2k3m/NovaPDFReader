package com.novapdf.reader.model

/**
 * Snapshot of persisted reader preferences that are frequently consulted during startup.
 *
 * @param nightMode true when the reader should launch in night mode, false when it should
 * default to light mode, or null if the preference has not been written yet.
 * @param lastDocumentUri the string representation of the most recently opened document, or
 * null when no history is available.
 */
data class UserPreferences(
    val nightMode: Boolean?,
    val lastDocumentUri: String?,
) {
    companion object {
        val EMPTY = UserPreferences(nightMode = null, lastDocumentUri = null)
    }
}
