package com.novapdf.reader.model

data class SearchResult(
    val pageIndex: Int,
    val matches: List<SearchMatch>
)

data class SearchMatch(
    val indexInPage: Int,
    val boundingBoxes: List<RectSnapshot>
)
