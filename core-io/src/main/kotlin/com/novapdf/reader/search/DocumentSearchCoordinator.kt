package com.novapdf.reader.search

import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.model.SearchResult

/**
 * High-level contract for document search so UI layers can depend on the API
 * without being tied to Lucene-specific implementation details.
 */
interface DocumentSearchCoordinator {
    fun prepare(session: PdfDocumentSession)
    suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult>
    fun dispose()
}
