package com.novapdf.reader.search

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

object SearchModuleContract : ModuleContract {
    override val api: ModuleApi = ModuleApi.SEARCH
    override val owner: String = "data:search"
    override val version: ModuleVersion = ModuleVersion(1, 0, 0)
}

@Module
@InstallIn(SingletonComponent::class)
object SearchContractModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideSearchContract(): ModuleContract = SearchModuleContract
}

