package com.novapdf.reader.model

/**
 * Describes the reader's persisted fallback behaviour when repeated faults make
 * the default adaptive renderer unstable.
 */
enum class FallbackMode {
    /** No fallback features are forced. */
    NONE,

    /**
     * Forces the legacy simple renderer which disables aggressive prefetching
     * and advanced adaptive effects in favour of a conservative pipeline.
     */
    LEGACY_SIMPLE_RENDERER,
}
