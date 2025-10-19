package com.novapdf.reader.di

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.ViewerCachePolicyConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.testing.TestInstallIn
import java.util.Locale

@Module
@TestInstallIn(
    components = [ViewModelComponent::class],
    replaces = [ViewerCachePolicyModule::class],
)
object StressViewerCachePolicyModule {

    @Provides
    fun provideViewerCachePolicyConfig(): ViewerCachePolicyConfig {
        val arguments = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()
        return buildConfig(arguments)
    }

    private fun buildConfig(arguments: Bundle?): ViewerCachePolicyConfig {
        val defaults = ViewerCachePolicyConfig()
        val pageMax = parsePositiveInt(arguments, "viewerPageCacheMaxBytes", "NOVAPDF_VIEWER_PAGE_CACHE_MAX_BYTES")
        val tileMax = parsePositiveInt(arguments, "viewerTileCacheMaxBytes", "NOVAPDF_VIEWER_TILE_CACHE_MAX_BYTES")
        val documentParallelism = parsePositiveInt(arguments, "viewerDocumentParallelism", "NOVAPDF_VIEWER_DOCUMENT_PARALLELISM")
        val renderParallelism = parsePositiveInt(arguments, "viewerRenderParallelism", "NOVAPDF_VIEWER_RENDER_PARALLELISM")
        val indexParallelism = parsePositiveInt(arguments, "viewerIndexParallelism", "NOVAPDF_VIEWER_INDEX_PARALLELISM")
        val pageEviction = parseEviction(arguments, "viewerPageCacheEviction", "NOVAPDF_VIEWER_PAGE_CACHE_EVICTION")
        val tileEviction = parseEviction(arguments, "viewerTileCacheEviction", "NOVAPDF_VIEWER_TILE_CACHE_EVICTION")

        return defaults.copy(
            documentLoadParallelism = documentParallelism ?: defaults.documentLoadParallelism,
            renderParallelism = renderParallelism ?: defaults.renderParallelism,
            indexParallelism = indexParallelism ?: defaults.indexParallelism,
            pageBitmap = defaults.pageBitmap.copy(
                maxSizeBytesOverride = pageMax ?: defaults.pageBitmap.maxSizeBytesOverride,
                eviction = pageEviction ?: defaults.pageBitmap.eviction,
            ),
            tileBitmap = defaults.tileBitmap.copy(
                maxSizeBytesOverride = tileMax ?: defaults.tileBitmap.maxSizeBytesOverride,
                eviction = tileEviction ?: defaults.tileBitmap.eviction,
            ),
        )
    }

    private fun parsePositiveInt(arguments: Bundle?, key: String, envKey: String): Int? {
        val raw = resolveRawValue(arguments, key, envKey)
        val value = raw?.toIntOrNull()
        return if (value != null && value > 0) {
            value
        } else {
            if (raw != null) {
                Log.w(TAG, "Ignoring invalid integer override for $key: $raw")
            }
            null
        }
    }

    private fun parseEviction(arguments: Bundle?, key: String, envKey: String): ViewerCachePolicyConfig.EvictionPolicy? {
        val raw = resolveRawValue(arguments, key, envKey)?.lowercase(Locale.US) ?: return null
        return when (raw) {
            "lru" -> ViewerCachePolicyConfig.EvictionPolicy.LRU
            "disabled", "off", "none" -> ViewerCachePolicyConfig.EvictionPolicy.DISABLED
            else -> {
                Log.w(TAG, "Ignoring invalid eviction override for $key: $raw")
                null
            }
        }
    }

    private fun resolveRawValue(arguments: Bundle?, key: String, envKey: String): String? {
        val candidates = sequenceOf(
            arguments?.getString(key),
            arguments?.getString(envKey),
            System.getenv(key),
            System.getenv(envKey),
        )
        return candidates
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }

    private const val TAG = "ViewerCachePolicy"
}
