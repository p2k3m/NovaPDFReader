package com.novapdf.reader.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class SensitiveDataSanitizerTest {

    @Test
    fun `redacts unix absolute paths while preserving filename`() {
        val sanitized = SensitiveDataSanitizer.sanitize("Cache path is /storage/emulated/0/Download/report.pdf")
        assertEquals("Cache path is <redacted-path:report.pdf>", sanitized)
    }

    @Test
    fun `redacts windows absolute paths`() {
        val sanitized = SensitiveDataSanitizer.sanitize("Unable to read C:\\temp\\secret\\notes.txt")
        assertEquals("Unable to read <redacted-path:notes.txt>", sanitized)
    }

    @Test
    fun `redacts bearer tokens`() {
        val sanitized = SensitiveDataSanitizer.sanitize("Authorization header was Bearer abc.def-123")
        assertEquals("Authorization header was Bearer <redacted-token>", sanitized)
    }

    @Test
    fun `redacts credential style assignments`() {
        val sanitized = SensitiveDataSanitizer.sanitize("aws_secret_access_key=abcd1234EFGH5678ijkl9012MNOP3456qrst7890")
        assertEquals("aws_secret_access_key=<redacted>", sanitized)
    }

    @Test
    fun `redacts credential query parameters`() {
        val sanitized = SensitiveDataSanitizer.sanitize("https://example.com/file?session_token=abc123&other=value")
        assertEquals("https://example.com/file?session_token=<redacted>&other=value", sanitized)
    }

    @Test
    fun `redacts credential assignments inside structured payloads`() {
        val sanitized = SensitiveDataSanitizer.sanitize("{\"sessionToken\":\"abc123\",\"metadata\":\"keep\"}")
        assertEquals("{\"sessionToken\":\"<redacted>\",\"metadata\":\"keep\"}", sanitized)
    }

    @Test
    fun `redacts credential assignments wrapped in single quotes`() {
        val sanitized = SensitiveDataSanitizer.sanitize("api_key='secret-value-123'")
        assertEquals("api_key='<redacted>'", sanitized)
    }

    @Test
    fun `formatMessage redacts embedded paths`() {
        val formatted = NovaLog.formatMessage(
            message = "Failed to persist /var/lib/app/cache.bin",
            fields = arrayOf(field("path", "/var/lib/app/cache.bin")),
        )
        assertEquals(
            "Failed to persist <redacted-path:cache.bin> | path=\"<redacted-path:cache.bin>\"",
            formatted,
        )
    }
}

