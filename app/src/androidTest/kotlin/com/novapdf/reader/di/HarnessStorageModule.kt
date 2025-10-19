package com.novapdf.reader.di

import android.content.Context
import com.novapdf.reader.HarnessOverrideRegistry
import com.novapdf.reader.data.remote.DelegatingStorageClient
import com.novapdf.reader.data.remote.FileStorageClient
import com.novapdf.reader.data.remote.HttpStorageClient
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.data.remote.di.StorageModule
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import kotlin.jvm.JvmSuppressWildcards

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [StorageModule::class],
)
object HarnessStorageModule {

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
        registry: HarnessOverrideRegistry,
        @ApplicationContext context: Context,
        clients: Set<@JvmSuppressWildcards StorageClient>,
    ): StorageClient {
        val delegates = clients.toList()
        registry.storageClientOverride(context, delegates)?.let { override ->
            return override
        }
        return DelegatingStorageClient(delegates)
    }
}
