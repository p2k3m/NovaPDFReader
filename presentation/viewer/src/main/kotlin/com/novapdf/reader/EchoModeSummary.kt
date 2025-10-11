package com.novapdf.reader

import android.content.res.Resources
import com.novapdf.reader.presentation.viewer.R
import kotlin.math.roundToInt

/**
 * Provides localized strings for the Echo Mode spoken summary.
 */
interface EchoModeSummaryStrings {
    fun pagePosition(pageNumber: Int, pageCount: Int): String
    fun readingPace(pagesPerMinute: Int): String
    fun bookmarkStatus(bookmarked: Boolean): String
    fun annotationSummary(annotationCount: Int): String?
    fun adaptiveFlowActive(): String
}

class ResourceEchoModeSummaryStrings(
    private val resources: Resources
) : EchoModeSummaryStrings {

    override fun pagePosition(pageNumber: Int, pageCount: Int): String {
        return resources.getQuantityString(
            R.plurals.echo_summary_page_position,
            pageCount,
            pageNumber,
            pageCount
        )
    }

    override fun readingPace(pagesPerMinute: Int): String {
        return resources.getQuantityString(
            R.plurals.echo_summary_reading_pace,
            pagesPerMinute,
            pagesPerMinute
        )
    }

    override fun bookmarkStatus(bookmarked: Boolean): String {
        val stringRes = if (bookmarked) {
            R.string.echo_summary_bookmarked
        } else {
            R.string.echo_summary_not_bookmarked
        }
        return resources.getString(stringRes)
    }

    override fun annotationSummary(annotationCount: Int): String? {
        if (annotationCount <= 0) return null
        return resources.getQuantityString(
            R.plurals.echo_summary_annotation_count,
            annotationCount,
            annotationCount
        )
    }

    override fun adaptiveFlowActive(): String {
        return resources.getString(R.string.echo_summary_adaptive_flow_active)
    }
}

/**
 * Builds a concise spoken summary of the current reading state for Echo Mode.
 */
fun PdfViewerUiState.echoSummary(strings: EchoModeSummaryStrings): String? {
    if (documentId == null || pageCount <= 0) return null

    val pageNumber = (currentPage + 1).coerceAtLeast(1)
    val annotationCount = activeAnnotations.count { it.pageIndex == currentPage }
    val bookmarked = bookmarks.contains(currentPage)

    val builder = StringBuilder()
        .append(strings.pagePosition(pageNumber, pageCount))

    if (readingSpeed > 0.5f) {
        val roundedSpeed = readingSpeed.roundToInt().coerceAtLeast(1)
        builder.append(' ')
            .append(strings.readingPace(roundedSpeed))
    }

    builder.append(' ')
        .append(strings.bookmarkStatus(bookmarked))

    strings.annotationSummary(annotationCount)?.let { annotationSummary ->
        builder.append(' ')
            .append(annotationSummary)
    }

    if (swipeSensitivity > 1.2f) {
        builder.append(' ')
            .append(strings.adaptiveFlowActive())
    }

    return builder.toString()
}
