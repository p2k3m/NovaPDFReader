package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TestDocumentFixtures {
    private const val THOUSAND_PAGE_CACHE = "stress-thousand-pages.pdf"
    private const val THOUSAND_PAGE_URL_ARG = "thousandPagePdfUrl"
    private const val THOUSAND_PAGE_COUNT = 1_000

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
            val downloaded = tryDownloadThousandPagePdf(tempFile)
            if (!downloaded) {
                writeThousandPagePdf(tempFile)
            }
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

    private fun tryDownloadThousandPagePdf(destination: File): Boolean {
        val url = InstrumentationRegistry.getArguments().getString(THOUSAND_PAGE_URL_ARG)
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val connection = URL(url).openConnection() as? HttpURLConnection ?: return false

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return false
            }

            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (destination.length() <= 0L) {
                destination.delete()
                return false
            }

            true
        } catch (_: IOException) {
            destination.delete()
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun writeThousandPagePdf(destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create cache directory for thousand-page PDF")
            }
        } ?: throw IOException("Missing cache directory for thousand-page PDF")

        val pdfDocument = PdfDocument()
        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val subtitlePaint = Paint(titlePaint).apply {
            textSize = 14f
            color = Color.DKGRAY
        }

        try {
            repeat(THOUSAND_PAGE_COUNT) { index ->
                val pageNumber = index + 1
                val pageInfo = PdfDocument.PageInfo.Builder(612, 792, pageNumber).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(Color.WHITE)
                canvas.drawText(
                    "Adaptive Flow benchmark page $pageNumber",
                    72f,
                    108f,
                    titlePaint
                )
                canvas.drawText(
                    "Total pages: $THOUSAND_PAGE_COUNT",
                    72f,
                    156f,
                    subtitlePaint
                )
                canvas.drawText(
                    "Generated for screenshot harness",
                    72f,
                    186f,
                    subtitlePaint
                )

                pdfDocument.finishPage(page)
            }

            destination.outputStream().use { outputStream ->
                BufferedOutputStream(outputStream).use { buffered ->
                    pdfDocument.writeTo(buffered)
                    buffered.flush()
                }
            }
        } catch (error: IOException) {
            destination.delete()
            throw error
        } finally {
            pdfDocument.close()
        }

        if (destination.length() <= 0L) {
            destination.delete()
            throw IOException("Failed to generate thousand-page PDF; destination is empty")
        }
    }
}
