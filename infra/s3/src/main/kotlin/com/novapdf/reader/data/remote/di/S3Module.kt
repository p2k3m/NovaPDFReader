package com.novapdf.reader.data.remote.di

import android.content.Context
import com.novapdf.reader.data.remote.PdfDownloadManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object S3Module {

    @Provides
    @Singleton
    fun providePdfDownloadManager(
        @ApplicationContext context: Context,
    ): PdfDownloadManager = PdfDownloadManager(context)
}
