package com.novapdf.reader.download.di

import com.novapdf.reader.data.remote.DocumentSourceHandler
import com.novapdf.reader.download.RemoteUrlDocumentSourceHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentSourceHandlerModule {

    @Binds
    @IntoSet
    @Singleton
    abstract fun bindRemoteUrlDocumentSourceHandler(
        impl: RemoteUrlDocumentSourceHandler,
    ): DocumentSourceHandler
}
