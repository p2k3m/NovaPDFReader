package com.novapdf.reader.cache.di

import android.content.Context
import com.novapdf.reader.cache.CacheDirectories
import com.novapdf.reader.cache.DefaultCacheDirectories
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideCacheDirectories(
        @ApplicationContext context: Context,
    ): CacheDirectories = DefaultCacheDirectories(context)
}
