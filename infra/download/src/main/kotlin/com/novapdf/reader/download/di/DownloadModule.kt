package com.novapdf.reader.download.di

import com.novapdf.reader.download.RemotePdfDownloader
import com.novapdf.reader.download.S3RemotePdfDownloader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    @Singleton
    abstract fun bindRemotePdfDownloader(
        impl: S3RemotePdfDownloader,
    ): RemotePdfDownloader
}
