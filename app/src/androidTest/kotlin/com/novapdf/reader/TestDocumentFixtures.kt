package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TestDocumentFixtures {
    private const val THOUSAND_PAGE_CACHE = "stress-thousand-pages.pdf"
    private const val THOUSAND_PAGE_COUNT = 1000

    suspend fun installThousandPageDocument(context: Context): android.net.Uri =
        withContext(Dispatchers.IO) {
            val candidateDirectories = writableStorageCandidates(context)

            val existing = candidateDirectories
                .map { File(it, THOUSAND_PAGE_CACHE) }
                .firstOrNull { it.exists() && it.length() > 0L }

            if (existing != null) {
                return@withContext existing.toUri()
            }

            val destinationDirectory = candidateDirectories.firstOrNull()
                ?: throw IOException("No writable internal storage directories available for thousand-page PDF")

            val destination = File(destinationDirectory, THOUSAND_PAGE_CACHE)
            destination.parentFile?.mkdirs()
            createThousandPagePdf(destination)
            destination.toUri()
        }

    private suspend fun createThousandPagePdf(destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for thousand-page PDF")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Unable to create cache directory for thousand-page PDF")
        }

        val tempFile = File(parentDir, destination.name + ".tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Unable to clear stale thousand-page PDF cache")
        }

        try {
            writeThousandPagePdf(tempFile)
        } catch (error: IOException) {
            tempFile.delete()
            throw error
        }

        if (destination.exists() && !destination.delete()) {
            tempFile.delete()
            throw IOException("Unable to replace cached thousand-page PDF")
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.delete()
            throw IOException("Unable to move thousand-page PDF into cache")
        }
    }

    private fun writeThousandPagePdf(destination: File) {
        val totalPages = THOUSAND_PAGE_COUNT
        val contentStreamCount = 1
        val totalObjects = 2 + totalPages + contentStreamCount

        destination.outputStream().buffered().use { stream ->
            var bytesWritten = 0L
            fun write(text: String) {
                val data = text.toByteArray(Charsets.US_ASCII)
                stream.write(data)
                bytesWritten += data.size
            }

            val objectOffsets = LongArray(totalObjects + 1)

            fun beginObject(index: Int, body: () -> Unit) {
                objectOffsets[index] = bytesWritten
                write("$index 0 obj\n")
                body()
                write("\nendobj\n")
            }

            write("%PDF-1.4\n")

            beginObject(1) {
                write("<< /Type /Catalog /Pages 2 0 R >>")
            }

            val firstPageObject = 3
            val sharedContentObject = firstPageObject + totalPages
            val kidsBuilder = StringBuilder("[")
            for (i in 0 until totalPages) {
                if (i > 0) {
                    kidsBuilder.append(' ')
                }
                kidsBuilder.append("${firstPageObject + i} 0 R")
                if ((i + 1) % 16 == 0 && i + 1 < totalPages) {
                    kidsBuilder.append("\n ")
                }
            }
            kidsBuilder.append(']')

            beginObject(2) {
                write("<< /Type /Pages /Count $totalPages /Kids ${kidsBuilder} >>")
            }

            repeat(totalPages) { index ->
                val objectIndex = firstPageObject + index
                beginObject(objectIndex) {
                    write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ")
                    write("/Contents ${sharedContentObject} 0 R /Resources << >> >>")
                }
            }

            beginObject(sharedContentObject) {
                write("<< /Length 0 >>\nstream\n\nendstream")
            }

            val startXref = bytesWritten
            write("xref\n")
            write("0 ${totalObjects + 1}\n")
            write("0000000000 65535 f \n")
            for (i in 1..totalObjects) {
                write(String.format("%010d 00000 n \n", objectOffsets[i]))
            }

            write("trailer\n")
            write("<< /Size ${totalObjects + 1} /Root 1 0 R >>\n")
            write("startxref\n")
            write("$startXref\n")
            write("%%EOF\n")
            stream.flush()
        }

        if (destination.length() <= 0L) {
            destination.delete()
            throw IOException("Failed to generate thousand-page PDF; destination is empty")
        }
    }
}
