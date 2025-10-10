package com.novapdf.reader.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.text.Charsets

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

    @Test
    fun `pdf header is accepted when starting with magic bytes`() {
        val header = "%PDF-1.7".toByteArray(Charsets.US_ASCII)
        assertTrue(hasPdfMagic(header, header.size))
    }

    @Test
    fun `pdf header allows bom and whitespace before magic bytes`() {
        val header = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), '\n'.code.toByte()) + "%PDF".toByteArray(Charsets.US_ASCII)
        assertTrue(hasPdfMagic(header, header.size))
    }

    @Test
    fun `non pdf header is rejected`() {
        val header = "PK\u0003\u0004".toByteArray(Charsets.US_ASCII)
        assertFalse(hasPdfMagic(header, header.size))
    }

    @Test
    fun `insufficient header length is rejected`() {
        val header = byteArrayOf('%'.code.toByte(), 'P'.code.toByte())
        assertFalse(hasPdfMagic(header, header.size))
    }
}
