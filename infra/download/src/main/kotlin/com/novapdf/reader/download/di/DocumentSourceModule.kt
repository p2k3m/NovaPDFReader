package com.novapdf.reader.download.di

import com.novapdf.reader.data.remote.DelegatingDocumentSourceGateway
import com.novapdf.reader.data.remote.DocumentSourceGateway
import com.novapdf.reader.data.remote.DocumentSourceHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@InstallIn(SingletonComponent::class)
object DocumentSourceModule {

    @Provides
    @Singleton
    fun provideDocumentSourceGateway(
        handlers: Set<@JvmSuppressWildcards DocumentSourceHandler>,
    ): DocumentSourceGateway = DelegatingDocumentSourceGateway(handlers)
}
