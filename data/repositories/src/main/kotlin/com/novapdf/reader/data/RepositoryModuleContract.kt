package com.novapdf.reader.data

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

object RepositoryModuleContract : ModuleContract {
    override val api: ModuleApi = ModuleApi.REPOSITORY
    override val owner: String = "data:repositories"
    override val version: ModuleVersion = ModuleVersion(1, 0, 0)
}

@Module
@InstallIn(SingletonComponent::class)
object RepositoryContractModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideRepositoryContract(): ModuleContract = RepositoryModuleContract
}

