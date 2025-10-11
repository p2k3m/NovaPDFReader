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

    data class RemoteUrl(val url: String) : DocumentSource {
        override val id: String = url
        override val kind: Kind = Kind.REMOTE_URL
    }

    data class GoogleDrive(val fileId: String) : DocumentSource {
        override val id: String = fileId
        override val kind: Kind = Kind.GOOGLE_DRIVE
    }

    data class Dropbox(val path: String) : DocumentSource {
        override val id: String = path
        override val kind: Kind = Kind.DROPBOX
    }
}
