package com.novapdf.reader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThousandPagePdfWriterTest {

    @Test
    fun `generated thousand page document is readable by pdfbox`() {
        val output = ByteArrayOutputStream()
        ThousandPagePdfWriter(1_000).writeTo(output)
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "writer produced empty pdf" }
        assertTrue(bytes.size < 10_000_000) { "expected compact fixture, size=${bytes.size}" }
        PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
            assertEquals(1_000, document.numberOfPages)
        }
    }
}
