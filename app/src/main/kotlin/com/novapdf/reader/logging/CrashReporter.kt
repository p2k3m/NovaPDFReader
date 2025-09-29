package com.novapdf.reader.logging

/**
 * Records crash and ANR diagnostics for the reader. This abstraction lets the production
 * application plug into Firebase Crashlytics (or another backend) while keeping unit tests
 * lightweight by injecting a no-op implementation.
 */
interface CrashReporter {
    /** Installs any process-wide hooks such as uncaught exception handlers. */
    fun install()

    /** Records a non-fatal exception with optional contextual metadata. */
    fun recordNonFatal(throwable: Throwable, metadata: Map<String, String> = emptyMap())

    /** Adds a breadcrumb style message to the crash log. */
    fun logBreadcrumb(message: String)
}
