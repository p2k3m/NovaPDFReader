package com.novapdf.reader.data.remote

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
    }
}

data class RemoteSourceDiagnostics(
    val failureCount: Int,
    val lastFailureReason: RemotePdfException.Reason?,
    val lastFailureMessage: String?,
)
