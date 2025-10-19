package com.novapdf.reader.data.remote.di

import android.content.Context
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.integration.aws.S3StorageClient
import com.novapdf.reader.cache.CacheDirectories
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object S3Module {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @IntoSet
    fun provideS3StorageClient(
        s3StorageClient: S3StorageClient,
    ): StorageClient = s3StorageClient

    @Provides
    @Singleton
    fun providePdfDownloadManager(
        @ApplicationContext context: Context,
        storageClient: StorageClient,
        cacheDirectories: CacheDirectories,
    ): PdfDownloadManager = PdfDownloadManager(context, storageClient, cacheDirectories)
}

private const val DOWNLOAD_CALL_TIMEOUT_SECONDS = 10L
