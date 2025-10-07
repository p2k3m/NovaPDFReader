package com.novapdf.reader

import kotlin.math.roundToInt

/**
 * Builds a concise spoken summary of the current reading state for Echo Mode.
 */
fun PdfViewerUiState.echoSummary(): String? {
    if (documentId == null || pageCount <= 0) return null

    val pageNumber = (currentPage + 1).coerceAtLeast(1)
    val annotationCount = activeAnnotations.count { it.pageIndex == currentPage }
    val bookmarked = bookmarks.contains(currentPage)

    val builder = StringBuilder()
        .append("Page ")
        .append(pageNumber)
        .append(" of ")
        .append(pageCount)
        .append('.')

    if (readingSpeed > 0.5f) {
        val roundedSpeed = readingSpeed.roundToInt().coerceAtLeast(1)
        builder.append(' ')
            .append("Reading pace ")
            .append(roundedSpeed)
            .append(if (roundedSpeed == 1) " page per minute." else " pages per minute.")
    }

    builder.append(' ')
        .append("This page is ")
        .append(if (bookmarked) "bookmarked." else "not bookmarked.")

    if (annotationCount > 0) {
        builder.append(' ')
            .append("Contains ")
            .append(annotationCount)
            .append(if (annotationCount == 1) " annotation." else " annotations.")
    }

    if (swipeSensitivity > 1.2f) {
        builder.append(' ').append("Adaptive Flow is actively adjusting page turns.")
    }

    return builder.toString()
}
