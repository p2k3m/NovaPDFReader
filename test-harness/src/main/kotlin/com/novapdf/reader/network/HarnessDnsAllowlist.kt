package com.novapdf.reader.network

import java.util.Locale

/**
 * Allowlist used by the screenshot harness DNS shim to decide whether a hostname should be
 * resolved on the device. Only Amazon S3 endpoints and local development hosts are permitted
 * to prevent the test harness from making arbitrary network requests.
 */
object HarnessDnsAllowlist {

    private val EXACT_HOSTS = setOf(
        "s3.amazonaws.com",
        "localhost",
        "127.0.0.1",
        "::1",
    )

    private const val AMAZONAWS_SUFFIX = ".amazonaws.com"
    private const val AMAZONAWS_CHINA_SUFFIX = ".amazonaws.com.cn"

    /** Returns true when [hostname] matches the allowlisted S3 or local host patterns. */
    fun isAllowedHost(hostname: String): Boolean {
        val normalized = hostname.lowercase(Locale.US).trim()
        if (normalized.isEmpty()) {
            return false
        }
        if (normalized in EXACT_HOSTS) {
            return true
        }
        if (isAmazonS3Host(normalized, AMAZONAWS_SUFFIX)) {
            return true
        }
        if (isAmazonS3Host(normalized, AMAZONAWS_CHINA_SUFFIX)) {
            return true
        }
        return false
    }

    private fun isAmazonS3Host(hostname: String, suffix: String): Boolean {
        if (!hostname.endsWith(suffix)) {
            return false
        }
        val prefix = hostname.removeSuffix(suffix)
        if (prefix.isEmpty()) {
            return false
        }
        if (prefix == "s3" || prefix.startsWith("s3-") || prefix.startsWith("s3.")) {
            return true
        }
        if (prefix.endsWith(".s3") || prefix.contains(".s3-") || prefix.contains(".s3.")) {
            return true
        }
        return false
    }
}
