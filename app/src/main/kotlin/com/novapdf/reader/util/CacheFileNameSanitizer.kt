package com.novapdf.reader.util

import com.novapdf.reader.logging.NovaLog

private const val TAG = "CacheFileNameSanitizer"
private const val DEFAULT_SAFE_FALLBACK = "cache.bin"
private val SAFE_PUNCTUATION = setOf('.', '-', '_')
private val MULTIPLE_UNDERSCORES = Regex("_+")
private val MULTIPLE_PERIODS = Regex("\\.+")

/**
 * Normalizes a cache file or directory name so it only contains a conservative set of characters
 * that work reliably across shell tooling and the on-device test harness. Exotic characters such as
 * whitespace are collapsed to underscores and any leading/trailing separator noise is trimmed.
 */
fun sanitizeCacheFileName(
    raw: String,
    fallback: String = DEFAULT_SAFE_FALLBACK,
    label: String = "cache file name",
): String {
    val candidate = raw.trim()
    val sanitizedCandidate = candidate.sanitized()
    if (sanitizedCandidate.isNotEmpty()) {
        if (sanitizedCandidate != candidate) {
            NovaLog.w(TAG, "Sanitized $label \"$raw\" to \"$sanitizedCandidate\"")
        }
        return sanitizedCandidate
    }

    val fallbackValue = fallback.trim().ifEmpty { DEFAULT_SAFE_FALLBACK }
    val sanitizedFallback = fallbackValue.sanitized()
    if (sanitizedFallback.isNotEmpty()) {
        if (candidate.isNotEmpty() && sanitizedFallback != candidate) {
            NovaLog.w(
                TAG,
                "Sanitized $label \"$raw\" to \"$sanitizedFallback\" (fallback applied)",
            )
        }
        return sanitizedFallback
    }

    NovaLog.w(
        TAG,
        "Unable to sanitize $label \"$raw\" or fallback \"$fallback\"; using $DEFAULT_SAFE_FALLBACK",
    )
    return DEFAULT_SAFE_FALLBACK
}

private fun String.sanitized(): String {
    if (isEmpty()) return ""
    val mapped = buildString(length) {
        for (character in this@sanitized) {
            when {
                character.isLetterOrDigit() -> append(character)
                character in SAFE_PUNCTUATION -> append(character)
                character.isWhitespace() -> append('_')
                else -> append('_')
            }
        }
    }

    return mapped
        .replace(MULTIPLE_UNDERSCORES, "_")
        .replace(MULTIPLE_PERIODS, ".")
        .trim('_', '.')
}
