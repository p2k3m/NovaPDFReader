package com.novapdf.reader

import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class ThousandPagePdfWriterTest {

    private lateinit var outputPath: Path

    @Before
    fun setUp() {
        outputPath = createTempFile(prefix = "thousand-page", suffix = ".pdf")
    }

    @After
    fun tearDown() {
        if (this::outputPath.isInitialized) {
            outputPath.deleteIfExists()
        }
    }

    @Test
    fun generatedPdfMatchesExpectedDigest() {
        outputPath.outputStream().use { stream ->
            ThousandPagePdfWriter(THOUSAND_PAGE_COUNT).writeTo(stream)
        }

        val digest = computeDigest(outputPath)
        assertEquals(
            "Digest mismatch for generated thousand-page PDF: $digest",
            EXPECTED_DIGEST,
            digest,
        )
    }

    private fun computeDigest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    private companion object {
        private const val THOUSAND_PAGE_COUNT = 1_000
        private const val EXPECTED_DIGEST = "7d6484d4a4a768062325fc6d0f51ad19f2c2da17b9dc1bcfb80740239db89089"
    }
}
