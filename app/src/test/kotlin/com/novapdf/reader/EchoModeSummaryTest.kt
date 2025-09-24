package com.novapdf.reader

import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import com.novapdf.reader.model.RectSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoModeSummaryTest {

    @Test
    fun summaryIsNullWhenDocumentMissing() {
        val state = PdfViewerUiState()

        assertEquals(null, state.echoSummary())
    }

    @Test
    fun summaryCapturesReadingContext() {
        val state = PdfViewerUiState(
            documentId = "doc-123",
            pageCount = 8,
            currentPage = 1,
            readingSpeed = 42.6f,
            swipeSensitivity = 1.5f,
            bookmarks = listOf(1, 5),
            activeAnnotations = listOf(
                AnnotationCommand.Text(
                    pageIndex = 1,
                    text = "Note",
                    position = PointSnapshot(0.2f, 0.3f),
                    color = 0xFFFF0000L
                ),
                AnnotationCommand.Highlight(
                    pageIndex = 1,
                    rect = RectSnapshot(0.1f, 0.1f, 0.5f, 0.2f),
                    color = 0xFFFFFF00L
                ),
                AnnotationCommand.Stroke(
                    pageIndex = 0,
                    points = listOf(PointSnapshot(0f, 0f)),
                    color = 0xFF0000FFL,
                    strokeWidth = 2f
                )
            )
        )

        val summary = state.echoSummary()

        assertNotNull(summary)
        summary!!
        assertTrue(summary.contains("Page 2 of 8"))
        assertTrue(summary.contains("Reading pace 43"))
        assertTrue(summary.contains("bookmarked"))
        assertTrue(summary.contains("Contains 2"))
        assertTrue(summary.contains("Adaptive Flow"))
    }
}
