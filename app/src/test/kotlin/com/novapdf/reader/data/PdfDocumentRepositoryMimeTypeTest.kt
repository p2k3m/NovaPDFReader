package com.novapdf.reader.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PdfDocumentRepositoryMimeTypeTest {

    @Test
    fun `reported pdf mime type is preserved`() {
        val result = normalizedMimeType("application/pdf", null)
        assertEquals("application/pdf", result)
    }

    @Test
    fun `ambiguous mime type falls back to pdf extension`() {
        val result = normalizedMimeType("application/octet-stream", "pdf")
        assertEquals("application/pdf", result)
    }

    @Test
    fun `ambiguous mime type without extension remains unknown`() {
        val result = normalizedMimeType("application/octet-stream", null)
        assertNull(result)
    }

    @Test
    fun `vendor specific pdf mime type normalizes to standard`() {
        val result = normalizedMimeType("application/x-pdf", null)
        assertEquals("application/pdf", result)
    }

    @Test
    fun `missing mime type uses pdf extension`() {
        val result = normalizedMimeType(null, "pdf")
        assertEquals("application/pdf", result)
    }
}
