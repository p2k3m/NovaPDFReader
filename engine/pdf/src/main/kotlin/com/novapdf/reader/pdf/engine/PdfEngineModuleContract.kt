package com.novapdf.reader.pdf.engine

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

object PdfEngineModuleContract : ModuleContract {
    override val api: ModuleApi = ModuleApi.RENDERING
    override val owner: String = "engine:pdf"
    override val version: ModuleVersion = ModuleVersion(1, 0, 0)
}

@Module
@InstallIn(SingletonComponent::class)
object PdfEngineContractModule {

    @Provides
    @Singleton
    @IntoSet
    fun providePdfEngineContract(): ModuleContract = PdfEngineModuleContract
}

