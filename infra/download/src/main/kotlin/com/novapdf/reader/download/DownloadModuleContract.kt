package com.novapdf.reader.download

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

object DownloadModuleContract : ModuleContract {
    override val api: ModuleApi = ModuleApi.DOWNLOAD
    override val owner: String = "infra:download"
    override val version: ModuleVersion = ModuleVersion(1, 0, 0)
}

@Module
@InstallIn(SingletonComponent::class)
object DownloadContractModule {

    @Provides
    @Singleton
    @IntoSet
    fun provideDownloadContract(): ModuleContract = DownloadModuleContract
}

