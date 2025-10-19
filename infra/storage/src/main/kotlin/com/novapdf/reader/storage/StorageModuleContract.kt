package com.novapdf.reader.storage

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

object StorageModuleContract : ModuleContract {
    override val api: ModuleApi = ModuleApi.STORAGE
    override val owner: String = "infra:storage"
    override val version: ModuleVersion = ModuleVersion(1, 0, 0)
}

@Module
@InstallIn(SingletonComponent::class)
object StorageContractModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideStorageContract(): ModuleContract = StorageModuleContract
}

