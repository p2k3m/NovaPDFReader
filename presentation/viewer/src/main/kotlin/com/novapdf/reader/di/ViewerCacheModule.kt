package com.novapdf.reader.di

import com.novapdf.reader.PageCacheKey
import com.novapdf.reader.PdfViewerViewModel
import com.novapdf.reader.TileCacheKey
import com.novapdf.reader.ViewerBitmapCacheFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object ViewerCacheModule {

    @Provides
    fun providePageBitmapCacheFactory(): ViewerBitmapCacheFactory<PageCacheKey> =
        PdfViewerViewModel.defaultPageBitmapCacheFactory()

    @Provides
    fun provideTileBitmapCacheFactory(): ViewerBitmapCacheFactory<TileCacheKey> =
        PdfViewerViewModel.defaultTileBitmapCacheFactory()
}
