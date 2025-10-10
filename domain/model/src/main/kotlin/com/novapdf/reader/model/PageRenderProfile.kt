package com.novapdf.reader.model

/**
 * Describes the expected visual fidelity for a rendered PDF page bitmap.
 */
enum class PageRenderProfile {
    /**
     * Prioritize high quality output with full color fidelity.
     */
    HIGH_DETAIL,

    /**
     * Optimize for memory usage where minor quality loss is acceptable.
     */
    LOW_DETAIL,
}
