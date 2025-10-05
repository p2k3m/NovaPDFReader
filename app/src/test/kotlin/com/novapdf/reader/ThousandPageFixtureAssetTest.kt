package com.novapdf.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThousandPageFixtureAssetTest {

    @Test
    fun `bundled thousand page fixture matches writer output`() {
        val assetFile = File("src/androidTest/assets/thousand_page_fixture.base64")
        require(assetFile.exists()) { "thousand_page_fixture.base64 missing from androidTest assets" }

        val assetPdfBytes = Base64.getMimeDecoder()
            .decode(assetFile.readText(StandardCharsets.US_ASCII))

        val generatedPdf = ByteArrayOutputStream().use { output ->
            ThousandPagePdfWriter(1_000).writeTo(output)
            output.toByteArray()
        }

        assertArrayEquals(generatedPdf, assetPdfBytes) {
            "Bundled thousand-page PDF diverges from ThousandPagePdfWriter output"
        }

        val pdfContents = assetPdfBytes.toString(StandardCharsets.ISO_8859_1)
        val kidsPattern = Regex("/Kids\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
        val referencePattern = Regex("\\d+\\s+\\d+\\s+R")
        val maxKids = kidsPattern.findAll(pdfContents)
            .map { referencePattern.findAll(it.groupValues[1]).count() }
            .maxOrNull() ?: 0

        assertTrue(maxKids <= 8) {
            "Expected balanced /Pages tree with <=8 kids per node but found $maxKids"
        }
    }
}
