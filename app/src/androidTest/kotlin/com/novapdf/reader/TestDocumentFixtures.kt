package com.novapdf.reader

import android.content.Context
import android.util.Base64
import android.util.Base64InputStream
import android.util.Log
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TestDocumentFixtures {
    private const val THOUSAND_PAGE_CACHE = "stress-thousand-pages.pdf"
    private const val THOUSAND_PAGE_URL_ARG = "thousandPagePdfUrl"
    private const val THOUSAND_PAGE_COUNT = 1_000
    private const val THOUSAND_PAGE_ASSET_BASE64 = "thousand_page_fixture.base64"
    private val DEFAULT_THOUSAND_PAGE_URL = BuildConfig.THOUSAND_PAGE_FIXTURE_URL
        .takeIf { it.isNotBlank() }

    suspend fun installThousandPageDocument(context: Context): android.net.Uri =
        withContext(Dispatchers.IO) {
            val candidateDirectories = writableStorageCandidates(context)
            Log.i(
                TAG,
                "Resolved ${candidateDirectories.size} candidate directories for thousand-page PDF: " +
                    candidateDirectories.joinToString { it.absolutePath }
            )

            val existing = candidateDirectories
                .map { File(it, THOUSAND_PAGE_CACHE) }
                .firstOrNull { candidate ->
                    if (!candidate.exists() || candidate.length() <= 0L) {
                        return@firstOrNull false
                    }

                    val valid = validateThousandPagePdf(candidate)
                    if (valid) {
                        Log.i(
                            TAG,
                            "Reusing cached thousand-page PDF at ${candidate.absolutePath} (size=${candidate.length()} bytes)"
                        )
                        true
                    } else {
                        Log.w(
                            TAG,
                            "Cached thousand-page PDF at ${candidate.absolutePath} failed validation; deleting corrupted artifact"
                        )
                        if (!candidate.delete()) {
                            Log.w(
                                TAG,
                                "Unable to delete corrupted thousand-page PDF at ${candidate.absolutePath}; future runs will attempt regeneration"
                            )
                        }
                        false
                    }
                }

            if (existing != null) {
                return@withContext existing.toUri()
            }

            val destinationDirectory = candidateDirectories.firstOrNull()
                ?: throw IOException("No writable internal storage directories available for thousand-page PDF")

            val destination = File(destinationDirectory, THOUSAND_PAGE_CACHE)
            destination.parentFile?.mkdirs()
            Log.i(TAG, "Creating thousand-page PDF at ${destination.absolutePath}")
            createThousandPagePdf(destination)

            if (!validateThousandPagePdf(destination)) {
                Log.w(
                    TAG,
                    "Validation failed after generating thousand-page PDF at ${destination.absolutePath}; removing artifact"
                )
                if (!destination.delete()) {
                    Log.w(
                        TAG,
                        "Unable to delete invalid thousand-page PDF at ${destination.absolutePath} after failed validation"
                    )
                }
                throw IOException("Generated thousand-page PDF failed validation")
            }

            Log.i(
                TAG,
                "Prepared thousand-page PDF at ${destination.absolutePath} (size=${destination.length()} bytes)"
            )
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

        val preparedFromWriter = tryGenerateThousandPagePdf(tempFile)

        if (!preparedFromWriter) {
            try {
                val preparedFromAsset = copyBundledThousandPagePdf(tempFile)
                val preparedFromNetwork = if (!preparedFromAsset) {
                    tryDownloadThousandPagePdf(tempFile)
                } else {
                    false
                }
                if (!preparedFromAsset && !preparedFromNetwork) {
                    Log.i(TAG, "Falling back to thousand-page writer after asset/network failures")
                    writeThousandPagePdf(tempFile)
                }
            } catch (error: IOException) {
                tempFile.delete()
                throw error
            }
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

    private fun tryGenerateThousandPagePdf(destination: File): Boolean {
        return try {
            Log.i(TAG, "Generating thousand-page PDF via local writer")
            writeThousandPagePdf(destination)
            true
        } catch (error: IOException) {
            destination.delete()
            Log.w(TAG, "Unable to generate thousand-page PDF via writer", error)
            false
        }
    }

    private fun tryDownloadThousandPagePdf(destination: File): Boolean {
        val url = InstrumentationRegistry.getArguments().getString(THOUSAND_PAGE_URL_ARG)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THOUSAND_PAGE_URL
            ?: run {
                Log.i(TAG, "No thousand-page PDF download URL supplied; skipping network fetch")
                return false
            }

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

            Log.i(TAG, "Downloading thousand-page PDF from $url")
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (destination.length() <= 0L) {
                destination.delete()
                Log.w(TAG, "Downloaded thousand-page PDF was empty; deleting corrupted artifact")
                return false
            }

            if (!validateThousandPagePdf(destination)) {
                destination.delete()
                Log.w(TAG, "Validation failed after downloading thousand-page PDF; removing artifact")
                return false
            }

            Log.i(TAG, "Successfully downloaded thousand-page PDF to ${destination.absolutePath}")
            true
        } catch (_: IOException) {
            destination.delete()
            Log.w(TAG, "Failed to download thousand-page PDF from $url; falling back to bundled assets")
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBundledThousandPagePdf(destination: File): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return try {
            Log.i(TAG, "Attempting to hydrate thousand-page PDF from bundled asset")
            instrumentation.context.assets.open(THOUSAND_PAGE_ASSET_BASE64).use { assetStream ->
                Base64InputStream(assetStream, Base64.DEFAULT).use { decodedStream ->
                    destination.outputStream().buffered().use { output ->
                        decodedStream.copyTo(output)
                        output.flush()
                    }
                }
            }
            if (!validateThousandPagePdf(destination)) {
                destination.delete()
                Log.w(TAG, "Bundled thousand-page PDF asset failed validation")
                false
            } else {
                Log.i(
                    TAG,
                    "Successfully restored thousand-page PDF from bundled asset (size=${destination.length()} bytes)"
                )
                true
            }
        } catch (error: IOException) {
            destination.delete()
            Log.w(TAG, "Unable to copy bundled thousand-page PDF fixture", error)
            false
        } catch (error: SecurityException) {
            destination.delete()
            Log.w(TAG, "Security exception copying bundled thousand-page PDF fixture", error)
            false
        } catch (error: Throwable) {
            destination.delete()
            Log.w(TAG, "Unexpected failure copying bundled thousand-page PDF fixture", error)
            false
        }
    }

    private fun validateThousandPagePdf(candidate: File): Boolean {
        if (!candidate.exists() || candidate.length() <= 0L) {
            Log.w(TAG, "Validation failed for thousand-page PDF; file missing or empty at ${candidate.absolutePath}")
            return false
        }

        val totalPagesMarker = "(Total pages: $THOUSAND_PAGE_COUNT)"
        val lastPageMarker = "(Page index: ${THOUSAND_PAGE_COUNT - 1})"
        val windowSize = maxOf(totalPagesMarker.length, lastPageMarker.length, 32)
        val buffer = ByteArray(16 * 1024)

        return try {
            var footerValid = false
            var totalPagesFound = false
            var lastPageFound = false

            candidate.inputStream().buffered().use { input ->
                val headerBytes = ByteArray(8)
                val headerRead = input.read(headerBytes)
                if (headerRead < 7) {
                    Log.w(TAG, "Validation failed; unable to read PDF header from ${candidate.absolutePath}")
                    return false
                }
                val headerText = String(headerBytes, 0, headerRead, StandardCharsets.ISO_8859_1)
                if (!headerText.startsWith("%PDF-1.")) {
                    Log.w(TAG, "Validation failed; unexpected PDF header in thousand-page document")
                    return false
                }

                var carry = headerText
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        break
                    }

                    val chunk = carry + String(buffer, 0, read, StandardCharsets.ISO_8859_1)
                    if (!footerValid && chunk.contains("%%EOF")) {
                        footerValid = true
                    }
                    if (!totalPagesFound && chunk.contains(totalPagesMarker)) {
                        totalPagesFound = true
                    }
                    if (!lastPageFound && chunk.contains(lastPageMarker)) {
                        lastPageFound = true
                    }

                    if (footerValid && totalPagesFound && lastPageFound) {
                        break
                    }

                    carry = if (chunk.length > windowSize) {
                        chunk.takeLast(windowSize)
                    } else {
                        chunk
                    }
                }
            }

            val valid = footerValid && totalPagesFound && lastPageFound
            if (!valid) {
                Log.w(
                    TAG,
                    "Validation failed for thousand-page PDF (footer=$footerValid, " +
                        "totalPages=$totalPagesFound, lastPage=$lastPageFound)"
                )
                return false
            }

            if (!validatePageTree(candidate)) {
                Log.w(
                    TAG,
                    "Validation failed for thousand-page PDF due to oversized /Kids arrays"
                )
                return false
            }

            Log.i(
                TAG,
                "Validated thousand-page PDF at ${candidate.absolutePath} (size=${candidate.length()} bytes)"
            )
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Validation of downloaded thousand-page PDF failed", error)
            false
        }
    }

    private fun validatePageTree(candidate: File): Boolean {
        val contents = try {
            candidate.readText(StandardCharsets.ISO_8859_1)
        } catch (error: IOException) {
            Log.w(TAG, "Unable to read thousand-page PDF for page tree validation", error)
            return false
        } catch (error: SecurityException) {
            Log.w(TAG, "Security exception while reading thousand-page PDF for validation", error)
            return false
        }

        val kidsMatcher = KIDS_ARRAY_PATTERN.matcher(contents)
        while (kidsMatcher.find()) {
            val kidsSection = kidsMatcher.group(1)
            val referenceMatcher = REFERENCE_PATTERN.matcher(kidsSection)
            var referenceCount = 0
            while (referenceMatcher.find()) {
                referenceCount++
                if (referenceCount > MAX_KIDS_PER_ARRAY) {
                    break
                }
            }
            if (referenceCount > MAX_KIDS_PER_ARRAY) {
                Log.w(
                    TAG,
                    "Detected oversized /Kids array with $referenceCount entries in ${candidate.absolutePath}"
                )
                return false
            }
        }
        return true
    }

    private fun writeThousandPagePdf(destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create cache directory for thousand-page PDF")
            }
        } ?: throw IOException("Missing cache directory for thousand-page PDF")

        try {
            destination.outputStream().use { outputStream ->
                ThousandPagePdfWriter(THOUSAND_PAGE_COUNT).writeTo(outputStream)
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
    private const val TAG = "TestDocumentFixtures"
    private val KIDS_ARRAY_PATTERN: Pattern =
        Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL)
    private val REFERENCE_PATTERN: Pattern =
        Pattern.compile("\\d+\\s+\\d+\\s+R")
    private const val MAX_KIDS_PER_ARRAY = 16
}
