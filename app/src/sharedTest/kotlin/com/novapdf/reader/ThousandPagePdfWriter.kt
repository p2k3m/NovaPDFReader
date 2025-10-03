package com.novapdf.reader

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Generates a deterministic thousand-page PDF without relying on [android.graphics.pdf.PdfDocument].
 *
 * The rendering APIs allocate an in-memory representation for the entire document, which can trigger
 * out-of-memory crashes on lower-resource emulators. This writer produces an equivalent stress document
 * directly in the PDF format so that it can be streamed to disk and opened safely in tests.
 */
internal class ThousandPagePdfWriter(
    private val pageCount: Int,
    private val pageWidth: Int = 612,
    private val pageHeight: Int = 792,
) {

    @Throws(IOException::class)
    fun writeTo(stream: OutputStream) {
        CountingOutputStream(BufferedOutputStream(stream)).use { output ->
            PdfStreamEncoder(output, pageCount, pageWidth, pageHeight).write()
            output.flush()
        }
    }

    private class CountingOutputStream(
        private val delegate: OutputStream
    ) : OutputStream(), Closeable {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            bytesWritten += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private class PdfStreamEncoder(
        private val output: CountingOutputStream,
        private val pageCount: Int,
        private val pageWidth: Int,
        private val pageHeight: Int,
    ) {
        private val fontObjectNumber = 3 + pageCount * 2
        private val totalObjects = fontObjectNumber
        private val objectOffsets = LongArray(totalObjects + 1)

        @Throws(IOException::class)
        fun write() {
            writeAscii("%PDF-1.4\n")
            // Indicate that the file may contain binary data per the PDF specification.
            writeAscii("%\u00E2\u00E3\u00CF\u00D3\n")

            writeCatalog()
            writePages()
            writePageObjects()
            writeContentStreams()
            writeFontObject()

            val startXref = output.bytesWritten
            writeAscii("xref\n")
            writeAscii("0 ${totalObjects + 1}\n")
            writeAscii("0000000000 65535 f\n")
            for (index in 1..totalObjects) {
                val offset = objectOffsets[index]
                writeAscii(String.format(Locale.US, "%010d 00000 n\n", offset))
            }
            writeAscii("trailer\n")
            writeAscii("<< /Size ${totalObjects + 1} /Root 1 0 R >>\n")
            writeAscii("startxref\n")
            writeAscii("$startXref\n")
            writeAscii("%%EOF\n")
        }

        private fun writeCatalog() {
            startObject(1)
            writeAscii("<< /Type /Catalog /Pages 2 0 R >>\n")
            endObject()
        }

        private fun writePages() {
            startObject(2)
            writeAscii("<< /Type /Pages /Count $pageCount /Kids [\n")
            for (pageIndex in 0 until pageCount) {
                val pageObjectNumber = pageObjectNumber(pageIndex)
                writeAscii("$pageObjectNumber 0 R\n")
            }
            writeAscii("] >>\n")
            endObject()
        }

        private fun writePageObjects() {
            for (pageIndex in 0 until pageCount) {
                val pageObjectNumber = pageObjectNumber(pageIndex)
                val contentObjectNumber = contentObjectNumber(pageIndex)
                startObject(pageObjectNumber)
                writeAscii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
                        "/Contents $contentObjectNumber 0 R /Resources << /Font << /F1 $fontObjectNumber 0 R >> >> >>\n"
                )
                endObject()
            }
        }

        private fun writeContentStreams() {
            for (pageIndex in 0 until pageCount) {
                val contentObjectNumber = contentObjectNumber(pageIndex)
                val contentBytes = buildPageContent(pageIndex + 1)
                startObject(contentObjectNumber)
                writeAscii("<< /Length ${contentBytes.size} >>\n")
                writeAscii("stream\n")
                output.write(contentBytes)
                writeAscii("\nendstream\n")
                endObject()
            }
        }

        private fun writeFontObject() {
            startObject(fontObjectNumber)
            writeAscii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\n")
            endObject()
        }

        private fun startObject(number: Int) {
            objectOffsets[number] = output.bytesWritten
            writeAscii("$number 0 obj\n")
        }

        private fun endObject() {
            writeAscii("endobj\n")
        }

        private fun pageObjectNumber(index: Int): Int = 3 + index

        private fun contentObjectNumber(index: Int): Int = 3 + pageCount + index

        private fun buildPageContent(pageNumber: Int): ByteArray {
            val content = buildString {
                append("BT /F1 24 Tf 72 720 Td (Adaptive Flow benchmark page $pageNumber) Tj ET\n")
                append("BT /F1 14 Tf 72 680 Td (Total pages: $pageCount) Tj ET\n")
                append("BT /F1 14 Tf 72 650 Td (Generated for screenshot harness) Tj ET\n")
                append("BT /F1 12 Tf 72 620 Td (Page index: ${pageNumber - 1}) Tj ET\n")
            }
            return content.toByteArray(StandardCharsets.US_ASCII)
        }

        private fun writeAscii(value: String) {
            output.write(value.toByteArray(StandardCharsets.US_ASCII))
        }
    }
}

