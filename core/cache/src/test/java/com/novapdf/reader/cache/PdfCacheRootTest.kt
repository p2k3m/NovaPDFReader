package com.novapdf.reader.cache

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory

class PdfCacheRootTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDirectory(prefix = "pdf-cache-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `purgeDirectory removes entries older than max age`() {
        val stale = File(tempDir, "stale.pdf").apply {
            writeBytes(ByteArray(8))
            setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60))
        }
        val recent = File(tempDir, "recent.pdf").apply {
            writeBytes(ByteArray(8))
        }

        CachePurger.purgeDirectory(
            directory = tempDir,
            maxBytes = Long.MAX_VALUE,
            maxAgeMs = TimeUnit.DAYS.toMillis(30),
            nowMs = System.currentTimeMillis(),
            tag = "PdfCacheRootTest",
        )

        assertFalse("stale file should be deleted", stale.exists())
        assertTrue("recent file should be kept", recent.exists())
    }

    @Test
    fun `purgeDirectory trims least recently used files when exceeding budget`() {
        val keep = File(tempDir, "keep.pdf").apply {
            writeBytes(ByteArray(4))
            setLastModified(System.currentTimeMillis())
        }
        val purgeFirst = File(tempDir, "purge_first.pdf").apply {
            writeBytes(ByteArray(6))
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2))
        }
        val purgeSecond = File(tempDir, "purge_second.pdf").apply {
            writeBytes(ByteArray(6))
            setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1))
        }

        val totalSize = listOf(keep, purgeFirst, purgeSecond).sumOf { it.length() }
        assertEquals(16, totalSize)

        CachePurger.purgeDirectory(
            directory = tempDir,
            maxBytes = 8,
            maxAgeMs = Long.MAX_VALUE,
            nowMs = System.currentTimeMillis(),
            tag = "PdfCacheRootTest",
        )

        assertTrue("recent file should remain", keep.exists())
        assertFalse("oldest file should be deleted first", purgeFirst.exists())
        assertFalse("second oldest file should be deleted to meet budget", purgeSecond.exists())
        assertEquals(keep.length(), tempDir.listFiles()?.sumOf { it.length() } ?: 0L)
    }
}
