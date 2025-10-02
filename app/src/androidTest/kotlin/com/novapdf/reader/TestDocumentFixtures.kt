package com.novapdf.reader

import android.content.Context
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.text.Charsets
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

        val totalPages = THOUSAND_PAGE_COUNT
        val totalObjects = 2 + (totalPages * 2) + 2
        val resourceObject = 3 + (totalPages * 2)
        val fontObject = resourceObject + 1
        val offsets = LongArray(totalObjects + 1)

        var offset = 0L

        try {
            BufferedOutputStream(destination.outputStream()).use { rawOutput ->
                fun writeAscii(value: String) {
                    val bytes = value.toByteArray(Charsets.US_ASCII)
                    rawOutput.write(bytes)
                    offset += bytes.size
                }

                fun beginObject(index: Int, writer: () -> Unit) {
                    offsets[index] = offset
                    writeAscii("$index 0 obj\n")
                    writer()
                    writeAscii("\nendobj\n")
                }

                writeAscii("%PDF-1.4\n")

                beginObject(1) {
                    writeAscii("<< /Type /Catalog /Pages 2 0 R >>")
                }

                beginObject(2) {
                    writeAscii("<< /Type /Pages /Count $totalPages /Kids [\n")
                    for (index in 0 until totalPages) {
                        val pageObject = 3 + (index * 2)
                        writeAscii(" $pageObject 0 R")
                        if ((index + 1) % 8 == 0) {
                            writeAscii("\n")
                        }
                    }
                    writeAscii("\n] >>")
                }

                for (index in 0 until totalPages) {
                    val pageObject = 3 + (index * 2)
                    val contentObject = pageObject + 1

                    beginObject(pageObject) {
                        writeAscii(
                            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentObject 0 R /Resources $resourceObject 0 R >>"
                        )
                    }

                    beginObject(contentObject) {
                        val text = "BT /F1 24 Tf 72 720 Td (Adaptive Flow benchmark page ${index + 1}) Tj ET\n"
                        val data = text.toByteArray(Charsets.US_ASCII)
                        writeAscii("<< /Length ${data.size} >>\nstream\n")
                        rawOutput.write(data)
                        offset += data.size
                        writeAscii("endstream\n")
                    }
                }

                beginObject(resourceObject) {
                    writeAscii("<< /Font << /F1 $fontObject 0 R >> >>")
                }

                beginObject(fontObject) {
                    writeAscii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
                }

                val startXref = offset
                writeAscii("xref\n")
                writeAscii("0 ${totalObjects + 1}\n")
                writeAscii("0000000000 65535 f \n")
                for (index in 1..totalObjects) {
                    writeAscii(String.format(Locale.US, "%010d %05d n \n", offsets[index], 0))
                }
                writeAscii("trailer\n<< /Size ${totalObjects + 1} /Root 1 0 R >>\nstartxref\n$startXref\n%%EOF\n")
                rawOutput.flush()
            }
        } catch (error: IOException) {
            destination.delete()
            throw error
        }

        if (destination.length() <= 0L) {
            destination.delete()
            throw IOException("Failed to generate thousand-page PDF; destination is empty")
        }
    }
}
