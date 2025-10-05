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
        // Pdfium can crash when /Pages nodes expose very large /Kids arrays. Build a balanced tree
        // with a small branching factor so the screenshot harness remains stable on older binaries.
        private val pageTree = buildPageTree()
        private val pageNodeCount = pageTree.allNodes.size
        private val pageObjectsStart = PAGE_NODES_START_OBJECT_NUMBER + pageNodeCount
        private val contentObjectsStart = pageObjectsStart + pageCount
        private val fontObjectNumber = contentObjectsStart + pageCount
        private val totalObjects = fontObjectNumber
        private val objectOffsets = LongArray(totalObjects + 1)

        @Throws(IOException::class)
        fun write() {
            writeAscii("%PDF-1.4\n")
            // Indicate that the file may contain binary data per the PDF specification.
            writeAscii("%\u00E2\u00E3\u00CF\u00D3\n")

            writeCatalog()
            writePageRoot()
            writePageNodes()
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
            startObject(CATALOG_OBJECT_NUMBER)
            writeAscii("<< /Type /Catalog /Pages ${ROOT_PAGES_OBJECT_NUMBER} 0 R >>\n")
            endObject()
        }

        private fun writePageRoot() {
            startObject(ROOT_PAGES_OBJECT_NUMBER)
            writeAscii("<< /Type /Pages /Count $pageCount /Kids [\n")
            pageTree.rootChildren.forEach { node ->
                writeAscii("${node.objectNumber} 0 R\n")
            }
            writeAscii("] >>\n")
            endObject()
        }

        private fun writePageNodes() {
            pageTree.allNodes.forEach { node ->
                startObject(node.objectNumber)
                val parentObject = node.parent?.objectNumber ?: ROOT_PAGES_OBJECT_NUMBER
                writeAscii(
                    "<< /Type /Pages /Parent $parentObject 0 R /Count ${node.totalPages} /Kids [\n"
                )
                if (node.isLeaf) {
                    for (pageIndex in node.pageIndices) {
                        val pageObjectNumber = pageObjectNumber(pageIndex)
                        writeAscii("$pageObjectNumber 0 R\n")
                    }
                } else {
                    node.children.forEach { child ->
                        writeAscii("${child.objectNumber} 0 R\n")
                    }
                }
                writeAscii("] >>\n")
                endObject()
            }
        }

        private fun writePageObjects() {
            pageTree.leafNodes.forEach { node ->
                for (pageIndex in node.pageIndices) {
                    val pageObjectNumber = pageObjectNumber(pageIndex)
                    val contentObjectNumber = contentObjectNumber(pageIndex)
                    startObject(pageObjectNumber)
                    writeAscii(
                        "<< /Type /Page /Parent ${node.objectNumber} 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
                            "/Contents $contentObjectNumber 0 R /Resources << /Font << /F1 $fontObjectNumber 0 R >> /ProcSet [/PDF /Text] >> >>\n"
                    )
                    endObject()
                }
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

        private fun pageObjectNumber(index: Int): Int = pageObjectsStart + index

        private fun contentObjectNumber(index: Int): Int = contentObjectsStart + index

        private fun buildPageContent(pageNumber: Int): ByteArray {
            val content = buildString {
                appendLine("BT /F1 24 Tf 72 720 Td (Adaptive Flow benchmark page $pageNumber) Tj ET")
                appendLine("BT /F1 14 Tf 72 680 Td (Total pages: $pageCount) Tj ET")
                appendLine("BT /F1 14 Tf 72 650 Td (Generated for screenshot harness) Tj ET")
                append("BT /F1 12 Tf 72 620 Td (Page index: ${pageNumber - 1}) Tj ET")
            }
            return normaliseLineEndings(content).toByteArray(StandardCharsets.US_ASCII)
        }

        private fun writeAscii(value: String) {
            output.write(normaliseLineEndings(value).toByteArray(StandardCharsets.US_ASCII))
        }

        private fun normaliseLineEndings(input: String): String {
            if (!input.contains('\n') && !input.contains('\r')) {
                return input
            }
            val withoutCarriageReturns = input.replace("\r\n", "\n").replace('\r', '\n')
            return withoutCarriageReturns.replace("\n", "\r\n")
        }

        private fun buildPageTree(): PageTree {
            val leafNodes = mutableListOf<PageTreeNode>()
            var start = 0
            while (start < pageCount) {
                val end = minOf(pageCount, start + MAX_CHILDREN_PER_NODE)
                leafNodes += PageTreeNode(
                    pageRange = start until end,
                    children = mutableListOf(),
                    totalPages = end - start
                )
                start = end
            }

            val allNodes = mutableListOf<PageTreeNode>()
            allNodes.addAll(leafNodes)

            var currentLevel = leafNodes.toList()
            while (currentLevel.size > MAX_CHILDREN_PER_NODE) {
                val nextLevel = mutableListOf<PageTreeNode>()
                var index = 0
                while (index < currentLevel.size) {
                    val sliceEnd = minOf(currentLevel.size, index + MAX_CHILDREN_PER_NODE)
                    val children = currentLevel.subList(index, sliceEnd)
                    val node = PageTreeNode(
                        pageRange = null,
                        children = children.toMutableList(),
                        totalPages = children.sumOf(PageTreeNode::totalPages)
                    )
                    children.forEach { child -> child.parent = node }
                    nextLevel += node
                    allNodes += node
                    index = sliceEnd
                }
                currentLevel = nextLevel
            }

            currentLevel.forEach { it.parent = null }

            var nextObjectNumber = PAGE_NODES_START_OBJECT_NUMBER
            allNodes.forEach { node ->
                node.objectNumber = nextObjectNumber++
            }

            return PageTree(
                rootChildren = currentLevel,
                allNodes = allNodes,
                leafNodes = leafNodes
            )
        }

        private data class PageTree(
            val rootChildren: List<PageTreeNode>,
            val allNodes: List<PageTreeNode>,
            val leafNodes: List<PageTreeNode>
        )

        private data class PageTreeNode(
            val pageRange: IntRange?,
            val children: MutableList<PageTreeNode>,
            val totalPages: Int,
            var parent: PageTreeNode? = null,
            var objectNumber: Int = 0
        ) {
            val isLeaf: Boolean get() = pageRange != null
            val pageIndices: IntRange get() = pageRange ?: IntRange.EMPTY
        }

        companion object {
            private const val CATALOG_OBJECT_NUMBER = 1
            private const val ROOT_PAGES_OBJECT_NUMBER = 2
            private const val PAGE_NODES_START_OBJECT_NUMBER = 3
            // Keep the /Kids arrays intentionally small so legacy Pdfium builds avoid deep recursion
            // and excessively large sibling lists when traversing the thousand-page stress document.
            private const val MAX_CHILDREN_PER_NODE = 8
        }
    }
}

