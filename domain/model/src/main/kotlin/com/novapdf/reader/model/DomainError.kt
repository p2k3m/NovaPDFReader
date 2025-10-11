package com.novapdf.reader.model

/**
 * Canonical set of error codes that the domain layer exposes to callers.
 */
enum class DomainErrorCode {
    IO_TIMEOUT,
    PDF_MALFORMED,
    RENDER_OOM,
}

/**
 * Domain-level exception that carries a [DomainErrorCode].
 */
class DomainException(
    val code: DomainErrorCode,
    cause: Throwable? = null,
) : RuntimeException(cause?.message ?: code.name, cause)
