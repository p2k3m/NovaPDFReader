package com.novapdf.reader.logging

import kotlin.text.RegexOption.IGNORE_CASE

/**
 * Removes or redacts sensitive information such as filesystem paths and credentials from log output.
 */
internal object SensitiveDataSanitizer {

    private val credentialKeywords = listOf(
        "token",
        "access[_-]?token",
        "session[_-]?token",
        "api[_-]?key",
        "access[_-]?key",
        "secret",
        "secret[_-]?key",
        "aws[_-]?access[_-]?key[_-]?id",
        "aws[_-]?secret[_-]?access[_-]?key",
        "x-amz-credential",
        "x-amz-security-token",
        "x-amz-signature",
    )

    private val unixPathRegex = Regex("(?<!:)(/[^/\\s\\\"'?#|]+)+")
    private val windowsPathRegex = Regex("[A-Za-z]:\\\\(?:[^\\\\\"']+\\\\)*[^\\\\\"']+")
    private val basicAuthRegex = Regex("(?<=//)[^/\\s:@]+:[^/\\s@]+@")
    private val bearerRegex = Regex("\\b(bearer)\\s+([A-Za-z0-9\\-._~+/]+=*)", IGNORE_CASE)
    private val queryCredentialRegex = Regex(
        "(?<=(?:${credentialKeywords.joinToString("|")})=)[^&\\s]+",
        IGNORE_CASE,
    )
    private val assignmentCredentialRegex = Regex(
        "((?:${credentialKeywords.joinToString("|")})\\s*[=:]\\s*)([^\\s,;&]+)",
        IGNORE_CASE,
    )
    private val awsAccessKeyRegex = Regex("AKIA[0-9A-Z]{16}")

    fun sanitize(input: String): String {
        var sanitized = input
        sanitized = unixPathRegex.replace(sanitized, ::redactPath)
        sanitized = windowsPathRegex.replace(sanitized, ::redactPath)
        sanitized = basicAuthRegex.replace(sanitized) { "<redacted-credentials>@" }
        sanitized = bearerRegex.replace(sanitized) { match ->
            "${match.groupValues[1]} <redacted-token>"
        }
        sanitized = queryCredentialRegex.replace(sanitized) { "<redacted>" }
        sanitized = assignmentCredentialRegex.replace(sanitized) { match ->
            "${match.groupValues[1]}<redacted>"
        }
        sanitized = awsAccessKeyRegex.replace(sanitized, "<redacted-aws-key>")
        return sanitized
    }

    private fun redactPath(match: MatchResult): String {
        val path = match.value
        if (path.startsWith("//")) {
            return path
        }
        val normalized = path.removePrefix("/")
        val firstSeparatorIndex = normalized.indexOfFirst { it == '/' || it == '\\' }
        val firstSegment = when {
            firstSeparatorIndex < 0 -> normalized
            else -> normalized.substring(0, firstSeparatorIndex)
        }
        if (firstSegment.contains('.') && !firstSegment.startsWith('.')) {
            return path
        }
        return redactPathValue(path)
    }

    private fun redactPathValue(path: String): String {
        val trimmed = path.trimEnd('/', '\\')
        if (trimmed.isEmpty()) return "<redacted-path>"
        val lastComponent = trimmed
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trimStart('/', '\\')
            .takeIf { component ->
                component.isNotEmpty() && !(trimmed == component && trimmed.endsWith(':'))
            }
        val suffix = lastComponent?.let { ":$it" } ?: ""
        return "<redacted-path$suffix>"
    }
}

