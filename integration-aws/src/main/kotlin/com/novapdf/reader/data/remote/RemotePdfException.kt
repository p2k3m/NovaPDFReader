package com.novapdf.reader.data.remote

class RemotePdfException(
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(reason.name, cause) {
    enum class Reason {
        NETWORK,
        CORRUPTED,
    }
}
