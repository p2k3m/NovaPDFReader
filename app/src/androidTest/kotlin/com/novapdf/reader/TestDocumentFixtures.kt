package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.novapdf.reader.search.PdfBoxInitializer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font

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
        createThousandPagePdf(context, destination)
        destination.toUri()
    }

    private suspend fun createThousandPagePdf(context: Context, destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for thousand-page PDF")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Unable to create cache directory for thousand-page PDF")
        }

        val tempFile = File(parentDir, destination.name + ".tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Unable to clear stale thousand-page PDF cache")
        }

        PdfBoxInitializer.ensureInitialized(context)

        val document = PDDocument()
        val font = PDType1Font.HELVETICA

        try {
            repeat(THOUSAND_PAGE_COUNT) { index ->
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)

                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(72f, page.mediaBox.height - 72f)
                    stream.showText("Stress Test Document")
                    stream.newLineAtOffset(0f, -24f)
                    stream.showText("Page ${index + 1}")
                    stream.endText()
                }
            }

            document.save(tempFile)
        } catch (error: IOException) {
            tempFile.delete()
            throw error
        } finally {
            document.close()
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
}
