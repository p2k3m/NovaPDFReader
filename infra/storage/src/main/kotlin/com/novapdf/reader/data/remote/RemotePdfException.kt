package com.novapdf.reader.data.remote

import java.io.IOException

/**
 * Represents failures encountered while downloading or validating a remote PDF along with a
 * machine-readable [Reason] and optional [diagnostics].
 */
class RemotePdfException(
    val reason: Reason,
    cause: Throwable? = null,
    val diagnostics: RemoteSourceDiagnostics? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        NETWORK,
        NETWORK_RETRY_EXHAUSTED,
        CORRUPTED,
        CIRCUIT_OPEN,
        UNSAFE,
        FILE_TOO_LARGE,
    }
}

data class RemoteSourceDiagnostics(
    val failureCount: Int,
    val lastFailureReason: RemotePdfException.Reason?,
    val lastFailureMessage: String?,
)

/** Raised when a downloaded PDF exceeds the configured safe file size limit. */
class RemotePdfTooLargeException(
    val sizeBytes: Long,
    val maxBytes: Long,
) : IOException("Downloaded PDF exceeds safe size limit: $sizeBytes bytes")

/** Raised when a downloaded PDF advertises features flagged as unsafe for local processing. */
class RemotePdfUnsafeException(
    val indicators: List<String>,
) : IOException(
    buildString {
        append("Downloaded PDF contains unsafe features")
        if (indicators.isNotEmpty()) {
            append(": ")
            append(indicators.joinToString())
        }
    }
)
