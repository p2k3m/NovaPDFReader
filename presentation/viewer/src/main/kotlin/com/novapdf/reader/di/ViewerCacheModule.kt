package com.novapdf.reader.di

import com.novapdf.reader.NonCachingBitmapCache
import com.novapdf.reader.PageCacheKey
import com.novapdf.reader.TileCacheKey
import com.novapdf.reader.ViewerBitmapCacheFactory
import com.novapdf.reader.ViewerCachePolicyConfig
import com.novapdf.reader.FractionalBitmapLruCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object ViewerCachePolicyModule {

    @Provides
    fun provideViewerCachePolicyConfig(): ViewerCachePolicyConfig = ViewerCachePolicyConfig()
}

@Module
@InstallIn(ViewModelComponent::class)
object ViewerCacheModule {

    @Provides
    fun providePageBitmapCacheFactory(
        policyConfig: ViewerCachePolicyConfig,
    ): ViewerBitmapCacheFactory<PageCacheKey> =
        createFactory(policyConfig.pageBitmap, metricsName = "viewer_page_cache")

    @Provides
    fun provideTileBitmapCacheFactory(
        policyConfig: ViewerCachePolicyConfig,
    ): ViewerBitmapCacheFactory<TileCacheKey> =
        createFactory(policyConfig.tileBitmap, metricsName = "viewer_tile_cache")

    private fun <K : Any> createFactory(
        policy: ViewerCachePolicyConfig.BitmapCachePolicy,
        metricsName: String,
    ): ViewerBitmapCacheFactory<K> {
        return when (policy.eviction) {
            ViewerCachePolicyConfig.EvictionPolicy.LRU ->
                ViewerBitmapCacheFactory { maxBytes, sizeCalculator ->
                    FractionalBitmapLruCache(maxBytes, sizeCalculator, metricsName)
                }

            ViewerCachePolicyConfig.EvictionPolicy.DISABLED ->
                ViewerBitmapCacheFactory { _, sizeCalculator ->
                    NonCachingBitmapCache(sizeCalculator, metricsName)
                }
        }
    }
}
