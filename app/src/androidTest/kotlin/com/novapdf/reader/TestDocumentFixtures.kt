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
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale
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
            val storageCandidates = resolveStorageCandidates(context)
            Log.i(
                TAG,
                "Resolved ${storageCandidates.size} candidate directories for thousand-page PDF: " +
                    storageCandidates.joinToString { candidate ->
                        val marker = if (candidate.preferred) "*" else ""
                        "${candidate.directory.absolutePath}$marker"
                    }
            )

            val orderedCandidates = storageCandidates.sortedByDescending { it.preferred }
            locateReusableFixture(orderedCandidates)?.let { reusable ->
                return@withContext reusable.toUri()
            }

            val destinationDirectory = orderedCandidates
                .firstOrNull { it.preferred }
                ?.directory
                ?: orderedCandidates.firstOrNull()?.directory
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

    private fun resolveStorageCandidates(context: Context): List<StorageCandidate> {
        val instrumentation = runCatching { InstrumentationRegistry.getInstrumentation() }.getOrNull()
        val targetPackageName = instrumentation?.targetContext?.packageName ?: context.packageName
        val candidateContexts = LinkedHashSet<Context>()
        candidateContexts += context.applicationContext
        candidateContexts += context
        instrumentation?.targetContext?.applicationContext?.let(candidateContexts::add)
        instrumentation?.targetContext?.let(candidateContexts::add)
        instrumentation?.context?.applicationContext?.let(candidateContexts::add)
        instrumentation?.context?.let(candidateContexts::add)

        return candidateContexts
            .filterNotNull()
            .flatMap { candidateContext ->
                writableStorageCandidates(candidateContext).map { directory ->
                    val canonical = runCatching { directory.canonicalFile }.getOrElse { directory }
                    val preferred = canonical.absolutePath.contains("/$targetPackageName/")
                    StorageCandidate(canonical, preferred)
                }
            }
            .distinctBy { it.directory.absolutePath }
    }

    private fun locateReusableFixture(candidates: List<StorageCandidate>): File? {
        if (candidates.isEmpty()) {
            return null
        }

        val preferred = candidates.filter(StorageCandidate::preferred)
        preferred.forEach { candidate ->
            findValidFixture(candidate)?.let { return it }
        }

        val fallbacks = candidates.filterNot(StorageCandidate::preferred)
        fallbacks.forEach { candidate ->
            val source = findValidFixture(candidate, logReuse = false) ?: return@forEach
            val destinationDirectory = preferred.firstOrNull()?.directory
            if (destinationDirectory != null && destinationDirectory != candidate.directory) {
                migrateFixture(source, destinationDirectory)?.let { return it }
            }
            Log.i(
                TAG,
                "Reusing cached thousand-page PDF at ${source.absolutePath} (size=${source.length()} bytes)"
            )
            return source
        }

        return null
    }

    private fun findValidFixture(candidate: StorageCandidate, logReuse: Boolean = true): File? {
        val file = File(candidate.directory, THOUSAND_PAGE_CACHE)
        if (!file.exists() || file.length() <= 0L) {
            return null
        }

        val valid = validateThousandPagePdf(file)
        if (valid) {
            if (logReuse) {
                Log.i(
                    TAG,
                    "Reusing cached thousand-page PDF at ${file.absolutePath} (size=${file.length()} bytes)"
                )
            }
            return file
        }

        Log.w(
            TAG,
            "Cached thousand-page PDF at ${file.absolutePath} failed validation; deleting corrupted artifact"
        )
        if (!file.delete()) {
            Log.w(
                TAG,
                "Unable to delete corrupted thousand-page PDF at ${file.absolutePath}; future runs will attempt regeneration"
            )
        }
        return null
    }

    private fun migrateFixture(source: File, destinationDirectory: File): File? {
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            Log.w(
                TAG,
                "Unable to prepare destination directory ${destinationDirectory.absolutePath} for thousand-page PDF migration"
            )
            return null
        }
        val destination = File(destinationDirectory, THOUSAND_PAGE_CACHE)
        return try {
            source.copyTo(destination, overwrite = true)
            if (validateThousandPagePdf(destination)) {
                Log.i(
                    TAG,
                    "Migrated cached thousand-page PDF from ${source.absolutePath} to ${destination.absolutePath}"
                )
                destination
            } else {
                Log.w(
                    TAG,
                    "Validation failed after migrating thousand-page PDF to ${destination.absolutePath}; removing artifact"
                )
                if (!destination.delete()) {
                    Log.w(
                        TAG,
                        "Unable to delete invalid thousand-page PDF at ${destination.absolutePath} after migration"
                    )
                }
                null
            }
        } catch (error: IOException) {
            Log.w(
                TAG,
                "Unable to migrate thousand-page PDF to ${destination.absolutePath}",
                error
            )
            if (destination.exists() && !destination.delete()) {
                Log.w(
                    TAG,
                    "Unable to clean up failed thousand-page PDF migration at ${destination.absolutePath}"
                )
            }
            null
        }
    }

    private data class StorageCandidate(
        val directory: File,
        val preferred: Boolean,
    )

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

        val bytes = try {
            candidate.readBytes()
        } catch (error: IOException) {
            Log.w(TAG, "Unable to read thousand-page PDF for validation", error)
            return false
        } catch (error: SecurityException) {
            Log.w(TAG, "Security exception while reading thousand-page PDF for validation", error)
            return false
        }

        if (bytes.isEmpty()) {
            Log.w(TAG, "Validation failed; thousand-page PDF is empty at ${candidate.absolutePath}")
            return false
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val digestHex = digest.toHexString()
        if (!digestHex.equals(EXPECTED_THOUSAND_PAGE_DIGEST, ignoreCase = true)) {
            Log.w(
                TAG,
                "Thousand-page PDF digest mismatch for ${candidate.absolutePath}; " +
                    "expected=$EXPECTED_THOUSAND_PAGE_DIGEST actual=${digestHex.lowercase(Locale.US)}"
            )
            return false
        }

        val contents = try {
            String(bytes, StandardCharsets.ISO_8859_1)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to decode thousand-page PDF for validation", error)
            return false
        }

        if (!contents.startsWith("%PDF-1.")) {
            Log.w(TAG, "Validation failed; unexpected PDF header in thousand-page document")
            return false
        }

        val footerValid = contents.contains("%%EOF")
        val totalPagesMarker = "(Total pages: $THOUSAND_PAGE_COUNT)"
        val lastPageMarker = "(Page index: ${THOUSAND_PAGE_COUNT - 1})"
        val totalPagesFound = contents.contains(totalPagesMarker)
        val lastPageFound = contents.contains(lastPageMarker)

        if (!footerValid || !totalPagesFound || !lastPageFound) {
            Log.w(
                TAG,
                "Validation failed for thousand-page PDF (footer=$footerValid, " +
                    "totalPages=$totalPagesFound, lastPage=$lastPageFound)"
            )
            return false
        }

        if (!validatePageTree(candidate, contents)) {
            Log.w(
                TAG,
                "Validation failed for thousand-page PDF due to oversized /Kids arrays"
            )
            return false
        }

        Log.i(
            TAG,
            "Validated thousand-page PDF at ${candidate.absolutePath} " +
                "(size=${candidate.length()} bytes, digest=${digestHex.lowercase(Locale.US)})"
        )
        return true
    }

    private fun validatePageTree(candidate: File, contents: String): Boolean {
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
    private const val EXPECTED_THOUSAND_PAGE_DIGEST =
        "7d6484d4a4a768062325fc6d0f51ad19f2c2da17b9dc1bcfb80740239db89089"
    private val KIDS_ARRAY_PATTERN: Pattern =
        Pattern.compile("/Kids\\s*\\[(.*?)\\]", Pattern.DOTALL)
    private val REFERENCE_PATTERN: Pattern =
        Pattern.compile("\\d+\\s+\\d+\\s+R")
    private const val MAX_KIDS_PER_ARRAY = 4

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }
}
