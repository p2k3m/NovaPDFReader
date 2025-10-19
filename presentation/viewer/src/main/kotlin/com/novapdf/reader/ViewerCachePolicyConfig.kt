package com.novapdf.reader

/**
 * Describes cache sizing and concurrency policies for the PDF viewer.
 */
data class ViewerCachePolicyConfig(
    val pageBitmap: BitmapCachePolicy = BitmapCachePolicy(),
    val tileBitmap: BitmapCachePolicy = BitmapCachePolicy(),
    val documentLoadParallelism: Int = DEFAULT_DOCUMENT_LOAD_PARALLELISM,
    val renderParallelism: Int = DEFAULT_RENDER_POOL_PARALLELISM,
    val indexParallelism: Int = DEFAULT_INDEX_POOL_PARALLELISM,
) {
    data class BitmapCachePolicy(
        val maxSizeBytesOverride: Int? = null,
        val eviction: EvictionPolicy = EvictionPolicy.LRU,
    )

    enum class EvictionPolicy {
        LRU,
        DISABLED,
    }
}

internal const val DEFAULT_DOCUMENT_LOAD_PARALLELISM = 1
internal const val DEFAULT_RENDER_POOL_PARALLELISM = 2
internal const val DEFAULT_INDEX_POOL_PARALLELISM = 1
internal const val DEFAULT_MAX_PAGE_CACHE_BYTES = 32 * 1024 * 1024
internal const val DEFAULT_MAX_TILE_CACHE_BYTES = 24 * 1024 * 1024
