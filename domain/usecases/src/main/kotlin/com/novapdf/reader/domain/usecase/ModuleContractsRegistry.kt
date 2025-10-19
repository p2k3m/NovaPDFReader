package com.novapdf.reader.domain.usecase

import com.novapdf.reader.contract.ModuleApi
import com.novapdf.reader.contract.ModuleContract
import com.novapdf.reader.contract.ModuleVersion
import com.novapdf.reader.contract.displayName
import com.novapdf.reader.contract.requireCompatible
import javax.inject.Inject
import javax.inject.Singleton

private const val DOMAIN_CONSUMER = "domain:usecases"

@Singleton
class ModuleContractsRegistry @Inject constructor(
    contracts: Set<@JvmSuppressWildcards ModuleContract>,
) {
    private val modules = contracts.associateBy { it.api }

    private val minimumVersions = mapOf(
        ModuleApi.REPOSITORY to ModuleVersion(1, 0, 0),
        ModuleApi.RENDERING to ModuleVersion(1, 0, 0),
        ModuleApi.DOWNLOAD to ModuleVersion(1, 0, 0),
        ModuleApi.STORAGE to ModuleVersion(1, 0, 0),
        ModuleApi.SEARCH to ModuleVersion(1, 0, 0),
    )

    fun verifyDomainUseCases() {
        minimumVersions.forEach { (api, minimum) ->
            val contract = modules[api]
                ?: throw IllegalStateException(
                    "Missing module contract for ${api.displayName()} required by $DOMAIN_CONSUMER."
                )
            contract.requireCompatible(DOMAIN_CONSUMER, minimum)
        }
    }

    fun getContract(api: ModuleApi): ModuleContract =
        modules[api] ?: throw IllegalStateException(
            "Module contract for ${api.displayName()} is not registered."
        )
}

