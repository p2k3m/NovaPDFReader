package com.novapdf.reader.model

/**
 * Logical origin for a document that can be resolved into a readable PDF.
 */
sealed interface DocumentSource {
    /** Identifier used for logging/analytics purposes. */
    val id: String

    /** Provider family for this source. */
    val kind: Kind

    enum class Kind {
        REMOTE_URL,
        GOOGLE_DRIVE,
        DROPBOX,
    }

    data class RemoteUrl(
        val url: String,
        val allowLargeFile: Boolean = false,
    ) : DocumentSource {
        override val id: String = url
        override val kind: Kind = Kind.REMOTE_URL

        fun withLargeFileConsent(): RemoteUrl = copy(allowLargeFile = true)
    }

    data class GoogleDrive(
        val fileId: String,
        val allowLargeFile: Boolean = false,
    ) : DocumentSource {
        override val id: String = fileId
        override val kind: Kind = Kind.GOOGLE_DRIVE

        fun withLargeFileConsent(): GoogleDrive = copy(allowLargeFile = true)
    }

    data class Dropbox(
        val path: String,
        val allowLargeFile: Boolean = false,
    ) : DocumentSource {
        override val id: String = path
        override val kind: Kind = Kind.DROPBOX

        fun withLargeFileConsent(): Dropbox = copy(allowLargeFile = true)
    }
}
