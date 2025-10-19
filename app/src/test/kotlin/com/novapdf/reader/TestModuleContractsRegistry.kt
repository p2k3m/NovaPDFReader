package com.novapdf.reader

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import com.novapdf.reader.domain.usecase.ModuleContractsRegistry

internal fun createTestModuleContractsRegistry(): ModuleContractsRegistry {
    val contracts = setOf(
        TestModuleContract(ModuleApi.REPOSITORY),
        TestModuleContract(ModuleApi.RENDERING),
        TestModuleContract(ModuleApi.DOWNLOAD),
        TestModuleContract(ModuleApi.STORAGE),
        TestModuleContract(ModuleApi.SEARCH),
    )
    return ModuleContractsRegistry(contracts)
}

private data class TestModuleContract(
    override val api: ModuleApi,
    override val owner: String = "test",
    override val version: ModuleVersion = ModuleVersion(1, 0, 0),
) : ModuleContract
