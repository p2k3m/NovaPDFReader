package com.novapdf.reader

import android.content.Context
import android.util.Base64
import android.util.Base64InputStream
import com.novapdf.reader.logging.NovaLog
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.BuildConfig
import com.novapdf.reader.CacheFileNames
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TestDocumentFixtures @Inject constructor() {
    private val defaultThousandPageUrl = BuildConfig.THOUSAND_PAGE_FIXTURE_URL
        .takeIf { it.isNotBlank() }

    suspend fun installThousandPageDocument(context: Context): android.net.Uri =
        runHarnessEntrySuspending("TestDocumentFixtures", "installThousandPageDocument") {
            withContext(Dispatchers.IO) {
                val storageCandidates = resolveStorageCandidates(context)
                val randomizedCandidates = DocumentOrderRandom
                    .shuffled("thousandPageStorageCandidates", storageCandidates)
                val (preferredCandidates, fallbackCandidates) = randomizedCandidates
                    .partition(StorageCandidate::preferred)
                val orderedCandidates = preferredCandidates + fallbackCandidates
                NovaLog.i(
                    TAG,
                    "Resolved ${orderedCandidates.size} candidate directories for thousand-page PDF: " +
                        orderedCandidates.joinToString { candidate ->
                            val marker = if (candidate.preferred) "*" else ""
                            "${candidate.directory.absolutePath}$marker"
                        }
                )

                locateReusableFixture(orderedCandidates)?.let { reusable ->
                    val accessible = ensureAccessibleForApp(context, reusable)
                    return@withContext accessible.toUri()
                }

                val destinationDirectory = orderedCandidates
                    .firstOrNull { it.preferred }
                    ?.directory
                    ?: orderedCandidates.firstOrNull()?.directory
                    ?: throw IOException("No writable internal storage directories available for thousand-page PDF")

                val destination = File(destinationDirectory, CacheFileNames.THOUSAND_PAGE_CACHE)
                destination.parentFile?.mkdirs()
                NovaLog.i(TAG, "Creating thousand-page PDF at ${destination.absolutePath}")
                createThousandPagePdf(destination)

                if (!validateThousandPagePdf(destination)) {
                    NovaLog.w(
                        TAG,
                        "Validation failed after generating thousand-page PDF at ${destination.absolutePath}; removing artifact"
                    )
                    if (!destination.delete()) {
                        NovaLog.w(
                            TAG,
                            "Unable to delete invalid thousand-page PDF at ${destination.absolutePath} after failed validation"
                        )
                    }
                    throw IOException("Generated thousand-page PDF failed validation")
                }

                NovaLog.i(
                    TAG,
                    "Prepared thousand-page PDF at ${destination.absolutePath} (size=${destination.length()} bytes)"
                )
                val accessible = ensureAccessibleForApp(context, destination)
                accessible.toUri()
            }
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
            NovaLog.i(
                TAG,
                "Reusing cached thousand-page PDF at ${source.absolutePath} (size=${source.length()} bytes)"
            )
            return source
        }

        return null
    }

    private fun ensureAccessibleForApp(context: Context, file: File): File {
        val packageName = context.packageName
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
        if (canonicalPath != null && canonicalPath.contains("/$packageName/")) {
            return file
        }

        val targetDirectories = buildList {
            add(File(context.cacheDir, CacheFileNames.HARNESS_CACHE_DIRECTORY))
            context.filesDir?.let { add(File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY)) }
            context.externalCacheDir?.let { add(File(it, CacheFileNames.HARNESS_CACHE_DIRECTORY)) }
            context.getExternalFilesDir(null)?.let { parent ->
                add(File(parent, CacheFileNames.HARNESS_CACHE_DIRECTORY))
            }
        }

        targetDirectories.forEach { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                NovaLog.w(TAG, "Unable to prepare harness cache directory at ${directory.absolutePath}")
                return@forEach
            }

            val destination = File(directory, CacheFileNames.THOUSAND_PAGE_CACHE)
            if (copyFixture(file, destination)) {
                NovaLog.i(
                    TAG,
                    "Relocated thousand-page PDF to ${destination.absolutePath} for application accessibility"
                )
                return destination
            }
        }

        NovaLog.w(
            TAG,
            "Unable to relocate thousand-page PDF into application storage; using ${file.absolutePath}"
        )
        return file
    }

    private fun copyFixture(source: File, destination: File): Boolean {
        return try {
            source.inputStream().use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            destination.length() > 0L && validateThousandPagePdf(destination)
        } catch (error: IOException) {
            NovaLog.w(TAG, "Unable to copy thousand-page PDF to ${destination.absolutePath}", error)
            if (destination.exists() && !destination.delete()) {
                NovaLog.w(TAG, "Failed to delete incomplete thousand-page PDF at ${destination.absolutePath}")
            }
            false
        }
    }

    private fun findValidFixture(candidate: StorageCandidate, logReuse: Boolean = true): File? {
        val file = File(candidate.directory, CacheFileNames.THOUSAND_PAGE_CACHE)
        if (!file.exists() || file.length() <= 0L) {
            return null
        }

        val valid = validateThousandPagePdf(file)
        if (valid) {
            if (logReuse) {
                NovaLog.i(
                    TAG,
                    "Reusing cached thousand-page PDF at ${file.absolutePath} (size=${file.length()} bytes)"
                )
            }
            return file
        }

        NovaLog.w(
            TAG,
            "Cached thousand-page PDF at ${file.absolutePath} failed validation; deleting corrupted artifact"
        )
        if (!file.delete()) {
            NovaLog.w(
                TAG,
                "Unable to delete corrupted thousand-page PDF at ${file.absolutePath}; future runs will attempt regeneration"
            )
        }
        return null
    }

    private fun migrateFixture(source: File, destinationDirectory: File): File? {
        if (!destinationDirectory.exists() && !destinationDirectory.mkdirs()) {
            NovaLog.w(
                TAG,
                "Unable to prepare destination directory ${destinationDirectory.absolutePath} for thousand-page PDF migration"
            )
            return null
        }
        val destination = File(destinationDirectory, CacheFileNames.THOUSAND_PAGE_CACHE)
        return try {
            source.copyTo(destination, overwrite = true)
            if (validateThousandPagePdf(destination)) {
                NovaLog.i(
                    TAG,
                    "Migrated cached thousand-page PDF from ${source.absolutePath} to ${destination.absolutePath}"
                )
                destination
            } else {
                NovaLog.w(
                    TAG,
                    "Validation failed after migrating thousand-page PDF to ${destination.absolutePath}; removing artifact"
                )
                if (!destination.delete()) {
                    NovaLog.w(
                        TAG,
                        "Unable to delete invalid thousand-page PDF at ${destination.absolutePath} after migration"
                    )
                }
                null
            }
        } catch (error: IOException) {
            NovaLog.w(
                TAG,
                "Unable to migrate thousand-page PDF to ${destination.absolutePath}",
                error
            )
            if (destination.exists() && !destination.delete()) {
                NovaLog.w(
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
        val preparedFromAsset = if (!preparedFromWriter) {
            copyBundledThousandPagePdf(tempFile)
        } else {
            false
        }
        val preparedFromNetwork = if (!preparedFromWriter && !preparedFromAsset) {
            tryDownloadThousandPagePdf(tempFile)
        } else {
            false
        }

        if (!preparedFromWriter && !preparedFromAsset && !preparedFromNetwork) {
            try {
                NovaLog.i(
                    TAG,
                    "Retrying thousand-page writer after asset and network fallbacks failed",
                )
                writeThousandPagePdf(tempFile)
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
            NovaLog.i(TAG, "Generating thousand-page PDF via local writer")
            writeThousandPagePdf(destination)
            true
        } catch (error: IOException) {
            destination.delete()
            NovaLog.w(TAG, "Unable to generate thousand-page PDF via writer", error)
            false
        }
    }

    private fun tryDownloadThousandPagePdf(destination: File): Boolean {
        val url = InstrumentationRegistry.getArguments().getString(THOUSAND_PAGE_URL_ARG)
            ?.takeIf { it.isNotBlank() }
            ?: defaultThousandPageUrl
            ?: run {
                NovaLog.i(TAG, "No thousand-page PDF download URL supplied; skipping network fetch")
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

            NovaLog.i(TAG, "Downloading thousand-page PDF from $url")
            connection.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            if (destination.length() <= 0L) {
                destination.delete()
                NovaLog.w(TAG, "Downloaded thousand-page PDF was empty; deleting corrupted artifact")
                return false
            }

            if (!validateThousandPagePdf(destination)) {
                destination.delete()
                NovaLog.w(TAG, "Validation failed after downloading thousand-page PDF; removing artifact")
                return false
            }

            NovaLog.i(TAG, "Successfully downloaded thousand-page PDF to ${destination.absolutePath}")
            true
        } catch (_: IOException) {
            destination.delete()
            NovaLog.w(TAG, "Failed to download thousand-page PDF from $url; falling back to bundled assets")
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBundledThousandPagePdf(destination: File): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return try {
            NovaLog.i(TAG, "Attempting to hydrate thousand-page PDF from bundled asset")
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
                NovaLog.w(TAG, "Bundled thousand-page PDF asset failed validation")
                false
            } else {
                NovaLog.i(
                    TAG,
                    "Successfully restored thousand-page PDF from bundled asset (size=${destination.length()} bytes)"
                )
                true
            }
        } catch (error: IOException) {
            destination.delete()
            NovaLog.w(TAG, "Unable to copy bundled thousand-page PDF fixture", error)
            false
        } catch (error: SecurityException) {
            destination.delete()
            NovaLog.w(TAG, "Security exception copying bundled thousand-page PDF fixture", error)
            false
        } catch (error: Throwable) {
            destination.delete()
            NovaLog.w(TAG, "Unexpected failure copying bundled thousand-page PDF fixture", error)
            false
        }
    }

    private fun validateThousandPagePdf(candidate: File): Boolean {
        if (!candidate.exists() || candidate.length() <= 0L) {
            NovaLog.w(TAG, "Validation failed for thousand-page PDF; file missing or empty at ${candidate.absolutePath}")
            return false
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val headerSample = StringBuilder(HEADER_SAMPLE_LIMIT)
        val markerBuffer = StringBuilder()
        val kidsBuffer = StringBuilder()
        val totalPagesMarker = "(Total pages: $THOUSAND_PAGE_COUNT)"
        val lastPageMarker = "(Page index: ${THOUSAND_PAGE_COUNT - 1})"
        var footerFound = false
        var totalPagesFound = false
        var lastPageFound = false
        var oversizedKidsCount: Int? = null
        var bytesRead = 0L

        try {
            candidate.inputStream().buffered().use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    digest.update(buffer, 0, read)
                    bytesRead += read

                    val chunk = String(buffer, 0, read, StandardCharsets.ISO_8859_1)

                    if (headerSample.length < HEADER_SAMPLE_LIMIT) {
                        val remaining = HEADER_SAMPLE_LIMIT - headerSample.length
                        headerSample.append(chunk.take(remaining))
                    }

                    if (!totalPagesFound && chunk.contains(totalPagesMarker)) {
                        totalPagesFound = true
                    }
                    if (!lastPageFound && chunk.contains(lastPageMarker)) {
                        lastPageFound = true
                    }

                    markerBuffer.append(chunk)
                    if (!footerFound && markerBuffer.contains(PDF_FOOTER_MARKER)) {
                        footerFound = true
                    }
                    if (!totalPagesFound && markerBuffer.contains(totalPagesMarker)) {
                        totalPagesFound = true
                    }
                    if (!lastPageFound && markerBuffer.contains(lastPageMarker)) {
                        lastPageFound = true
                    }
                    if (markerBuffer.length > MARKER_BUFFER_LIMIT) {
                        markerBuffer.delete(0, markerBuffer.length - MARKER_BUFFER_LIMIT)
                    }

                    if (oversizedKidsCount == null) {
                        kidsBuffer.append(chunk)
                        oversizedKidsCount = processKidsBuffer(kidsBuffer)
                    }
                }
            }
        } catch (error: IOException) {
            NovaLog.w(TAG, "Unable to read thousand-page PDF for validation", error)
            return false
        } catch (error: SecurityException) {
            NovaLog.w(TAG, "Security exception while reading thousand-page PDF for validation", error)
            return false
        }

        if (bytesRead <= 0L) {
            NovaLog.w(TAG, "Validation failed; thousand-page PDF is empty at ${candidate.absolutePath}")
            return false
        }

        val digestHex = digest.digest().toHexString()
        if (!digestHex.equals(EXPECTED_THOUSAND_PAGE_DIGEST, ignoreCase = true)) {
            NovaLog.w(
                TAG,
                "Thousand-page PDF digest mismatch for ${candidate.absolutePath}; " +
                    "expected=$EXPECTED_THOUSAND_PAGE_DIGEST actual=${digestHex.lowercase(Locale.US)}"
            )
            return false
        }

        if (!headerSample.startsWith(PDF_HEADER_PREFIX)) {
            NovaLog.w(TAG, "Validation failed; unexpected PDF header in thousand-page document")
            return false
        }

        if (!footerFound || !totalPagesFound || !lastPageFound) {
            NovaLog.w(
                TAG,
                "Validation failed for thousand-page PDF (footer=$footerFound, " +
                    "totalPages=$totalPagesFound, lastPage=$lastPageFound)"
            )
            return false
        }

        if (oversizedKidsCount != null) {
            NovaLog.w(
                TAG,
                "Detected oversized /Kids array with $oversizedKidsCount entries in ${candidate.absolutePath}"
            )
            return false
        }

        NovaLog.i(
            TAG,
            "Validated thousand-page PDF at ${candidate.absolutePath} " +
                "(size=${candidate.length()} bytes, digest=${digestHex.lowercase(Locale.US)})"
        )
        return true
    }

    private fun processKidsBuffer(buffer: StringBuilder): Int? {
        while (true) {
            val kidsIndex = buffer.indexOf("/Kids")
            if (kidsIndex == -1) {
                if (buffer.length > KIDS_BUFFER_RETAIN) {
                    buffer.delete(0, buffer.length - KIDS_BUFFER_RETAIN)
                }
                return null
            }

            val openIndex = buffer.indexOf("[", kidsIndex)
            if (openIndex == -1) {
                if (kidsIndex > 0) {
                    buffer.delete(0, kidsIndex)
                }
                return null
            }

            val closeIndex = buffer.indexOf("]", openIndex + 1)
            if (closeIndex == -1) {
                if (kidsIndex > 0) {
                    buffer.delete(0, kidsIndex)
                }
                return null
            }

            val section = buffer.substring(openIndex + 1, closeIndex)
            val referenceMatcher = REFERENCE_PATTERN.matcher(section)
            var referenceCount = 0
            while (referenceMatcher.find()) {
                referenceCount++
                if (referenceCount > MAX_KIDS_PER_ARRAY) {
                    return referenceCount
                }
            }

            buffer.delete(0, closeIndex + 1)
        }
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

    private companion object {
        private const val THOUSAND_PAGE_URL_ARG = "thousandPagePdfUrl"
        private const val THOUSAND_PAGE_COUNT = 1_000
        private const val THOUSAND_PAGE_ASSET_BASE64 = "thousand_page_fixture.base64"
        private const val TAG = "TestDocumentFixtures"
        private const val EXPECTED_THOUSAND_PAGE_DIGEST =
            "7d6484d4a4a768062325fc6d0f51ad19f2c2da17b9dc1bcfb80740239db89089"
        private const val PDF_HEADER_PREFIX = "%PDF-1."
        private const val PDF_FOOTER_MARKER = "%%EOF"
        private const val HEADER_SAMPLE_LIMIT = 64
        private const val MARKER_BUFFER_LIMIT = 8 * 1024
        private const val KIDS_BUFFER_RETAIN = 4 * 1024
        private val REFERENCE_PATTERN: Pattern =
            Pattern.compile("\\d+\\s+\\d+\\s+R")
        private const val MAX_KIDS_PER_ARRAY = 4
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }
}
