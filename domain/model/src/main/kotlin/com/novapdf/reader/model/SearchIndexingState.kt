package com.novapdf.reader.model

sealed interface SearchIndexingState {
    object Idle : SearchIndexingState

    data class InProgress(
        val documentId: String,
        val progress: Float?,
        val phase: SearchIndexingPhase,
    ) : SearchIndexingState
}

enum class SearchIndexingPhase {
    PREPARING,
    EXTRACTING_TEXT,
    APPLYING_OCR,
    WRITING_INDEX,
}
