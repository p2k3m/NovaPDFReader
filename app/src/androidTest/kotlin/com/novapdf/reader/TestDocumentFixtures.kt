package com.novapdf.reader

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
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
        val candidateDirectories = internalStorageCandidates(context)

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

    private fun internalStorageCandidates(context: Context): List<File> = buildList {
        context.cacheDir?.let(::add)
        context.filesDir?.let(::add)
        context.codeCacheDir?.let(::add)
        context.noBackupFilesDir?.let(::add)
    }.distinct()

    private fun createThousandPagePdf(destination: File) {
        val pdf = PdfDocument()
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = 14f
        }
        try {
            repeat(THOUSAND_PAGE_COUNT) { index ->
                val pageInfo = PdfDocument.PageInfo.Builder(612, 792, index + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                val header = "Stress Test Document"
                canvas.drawText(header, 72f, 72f, paint)
                canvas.drawText("Page ${index + 1}", 72f, 108f, paint)
                pdf.finishPage(page)
            }
            destination.outputStream().use { output ->
                pdf.writeTo(output)
                output.flush()
            }
        } catch (error: IOException) {
            destination.delete()
            throw error
        } finally {
            pdf.close()
        }
    }
}
