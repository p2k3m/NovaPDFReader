package com.novapdf.reader.data.remote.di

import com.novapdf.reader.data.remote.DelegatingStorageClient
import com.novapdf.reader.data.remote.FileStorageClient
import com.novapdf.reader.data.remote.HttpStorageClient
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.StorageClientEngine
import com.novapdf.reader.data.remote.StorageEngine
import com.novapdf.reader.data.remote.DelegatingStorageEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideFileStorageClient(fileStorageClient: FileStorageClient): StorageClient = fileStorageClient

    @Provides
    @Singleton
    @IntoSet
    fun provideHttpStorageClient(httpStorageClient: HttpStorageClient): StorageClient = httpStorageClient

    @Provides
    @Singleton
    fun provideDelegatingStorageClient(
        clients: Set<@JvmSuppressWildcards StorageClient>,
    ): StorageClient = DelegatingStorageClient(clients.toList())

    @Provides
    @Singleton
    fun provideStorageEngine(
        clients: Set<@JvmSuppressWildcards StorageClient>,
    ): StorageEngine {
        val engines = clients.map { StorageClientEngine(it) }
        return DelegatingStorageEngine(engines)
    }
}
