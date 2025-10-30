package com.novapdf.reader

/**
 * Shared constants for automation entry points that allow CI to open documents via intents.
 */
object AutomationIntents {
    const val ACTION_VIEW_LOCAL_DOCUMENT = "com.novapdf.reader.action.VIEW_LOCAL_DOCUMENT"
    const val EXTRA_DOCUMENT_URI = "com.novapdf.reader.extra.DOCUMENT_URI"
    const val EXTRA_DOCUMENT_PATH = "com.novapdf.reader.extra.DOCUMENT_PATH"
}
