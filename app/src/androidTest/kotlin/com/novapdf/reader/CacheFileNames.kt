package com.novapdf.reader

import com.novapdf.reader.util.sanitizeCacheFileName

internal object CacheFileNames {
    val THOUSAND_PAGE_CACHE: String = sanitizeCacheFileName(
        raw = "stress-thousand-pages.pdf",
        fallback = "stress-thousand-pages.pdf",
        label = "thousand-page cache file",
    )

    val HARNESS_CACHE_DIRECTORY: String = sanitizeCacheFileName(
        raw = "screenshot-harness",
        fallback = "screenshot-harness",
        label = "screenshot harness cache directory",
    )

    val INSTRUMENTATION_SCREENSHOT_DIRECTORY: String = sanitizeCacheFileName(
        raw = "instrumentation-screenshots",
        fallback = "instrumentation-screenshots",
        label = "instrumentation screenshot directory",
    )

    val PROGRAMMATIC_SCREENSHOT_FILE: String = sanitizeCacheFileName(
        raw = "programmatic_screenshot.png",
        fallback = "programmatic_screenshot.png",
        label = "programmatic screenshot file",
    )

    val SCREENSHOT_READY_FLAG: String = sanitizeCacheFileName(
        raw = "screenshot_ready.flag",
        fallback = "screenshot_ready.flag",
        label = "screenshot ready flag",
    )

    val SCREENSHOT_DONE_FLAG: String = sanitizeCacheFileName(
        raw = "screenshot_done.flag",
        fallback = "screenshot_done.flag",
        label = "screenshot done flag",
    )

    val SAMPLE_PDF_CACHE: String = sanitizeCacheFileName(
        raw = "sample.pdf",
        fallback = "sample.pdf",
        label = "sample PDF cache file",
    )

    val SAMPLE_SCREENSHOT_FILE: String = sanitizeCacheFileName(
        raw = "sample_page.png",
        fallback = "sample_page.png",
        label = "sample screenshot file",
    )

    val STRESS_PDF_CACHE: String = sanitizeCacheFileName(
        raw = "stress-large.pdf",
        fallback = "stress-large.pdf",
        label = "stress PDF cache file",
    )
}
