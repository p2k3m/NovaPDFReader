package com.novapdf.reader.data.remote

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DelegatingStorageEngineTest {

    @Test
    fun `delegates to first supporting engine`() {
        runBlocking {
            val uri = uriWithScheme("s3")
            val expected = ByteArrayInputStream(ByteArray(0))
            val primary = object : StorageEngine {
                override val name: String = "primary"
                override fun canOpen(uri: Uri): Boolean = uri.scheme == "s3"
                override suspend fun open(uri: Uri) = expected
            }
            val fallback = object : StorageEngine {
                override val name: String = "fallback"
                override fun canOpen(uri: Uri): Boolean = true
                override suspend fun open(uri: Uri) = ByteArrayInputStream(byteArrayOf(1))
            }
            val engine = DelegatingStorageEngine(primary, fallback)

            val stream = engine.open(uri)

            assertSame(expected, stream)
        }
    }

    @Test
    fun `falls back when primary throws`() {
        runBlocking {
            val uri = uriWithScheme("https")
            val fallbackStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))
            val primary = object : StorageEngine {
                override val name: String = "primary"
                override fun canOpen(uri: Uri): Boolean = uri.scheme == "https"
                override suspend fun open(uri: Uri): ByteArrayInputStream {
                    throw IOException("boom")
                }
            }
            val fallback = object : StorageEngine {
                override val name: String = "fallback"
                override fun canOpen(uri: Uri): Boolean = true
                override suspend fun open(uri: Uri): ByteArrayInputStream = fallbackStream
            }
            val engine = DelegatingStorageEngine(primary, fallback)

            val stream = engine.open(uri)

            assertSame(fallbackStream, stream)
        }
    }

    @Test(expected = IOException::class)
    fun `propagates last error when all engines fail`() {
        runBlocking {
            val uri = uriWithScheme("https")
            val primary = object : StorageEngine {
                override val name: String = "primary"
                override fun canOpen(uri: Uri): Boolean = true
                override suspend fun open(uri: Uri): ByteArrayInputStream {
                    throw IOException("primary")
                }
            }
            val secondary = object : StorageEngine {
                override val name: String = "secondary"
                override fun canOpen(uri: Uri): Boolean = true
                override suspend fun open(uri: Uri): ByteArrayInputStream {
                    throw IOException("secondary")
                }
            }

            val engine = DelegatingStorageEngine(primary, secondary)

            try {
                engine.open(uri)
            } catch (error: IOException) {
                assertEquals("secondary", error.message)
                throw error
            }
        }
    }

    @Test
    fun `canOpen reports true when any engine supports uri`() {
        val uri = uriWithScheme("file")
        val primary = object : StorageEngine {
            override val name: String = "primary"
            override fun canOpen(uri: Uri): Boolean = false
            override suspend fun open(uri: Uri): ByteArrayInputStream = ByteArrayInputStream(byteArrayOf())
        }
        val secondary = object : StorageEngine {
            override val name: String = "secondary"
            override fun canOpen(uri: Uri): Boolean = uri.scheme == "file"
            override suspend fun open(uri: Uri): ByteArrayInputStream = ByteArrayInputStream(byteArrayOf())
        }

        val engine = DelegatingStorageEngine(primary, secondary)

        assertTrue(engine.canOpen(uri))
    }
    private fun uriWithScheme(scheme: String): Uri {
        val uri = mock<Uri>()
        whenever(uri.scheme).thenReturn(scheme)
        return uri
    }
}
