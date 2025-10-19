package com.novapdf.reader.contract

import java.util.Locale

/** Identifies the cross-module APIs exposed within the Nova PDF Reader stack. */
enum class ModuleApi {
    REPOSITORY,
    RENDERING,
    DOWNLOAD,
    STORAGE,
    SEARCH,
}

/**
 * Semantic version for a module contract. Versions follow a strict major.minor.patch scheme and
 * only non-negative components are permitted.
 */
data class ModuleVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
) : Comparable<ModuleVersion> {

    init {
        require(major >= 0) { "major must be non-negative" }
        require(minor >= 0) { "minor must be non-negative" }
        require(patch >= 0) { "patch must be non-negative" }
    }

    override fun compareTo(other: ModuleVersion): Int {
        if (major != other.major) {
            return major.compareTo(other.major)
        }
        if (minor != other.minor) {
            return minor.compareTo(other.minor)
        }
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

/** Declares the cross-module contract implemented by a concrete module. */
interface ModuleContract {
    val api: ModuleApi
    val owner: String
    val version: ModuleVersion
}

/**
 * Exception thrown when a consumer detects an incompatible module contract implementation at
 * runtime.
 */
class ModuleContractViolationException(
    val api: ModuleApi,
    val owner: String,
    val actualVersion: ModuleVersion,
    val requiredVersion: ModuleVersion,
    val consumer: String,
) : IllegalStateException(
    "Module contract mismatch: $consumer requires ${api.displayName()} >= $requiredVersion " +
        "but $owner provides $actualVersion."
)

/** Returns the locale-stable human readable identifier for this API. */
fun ModuleApi.displayName(): String = name.lowercase(Locale.US)

/**
 * Ensures that the [ModuleContract.version] is compatible with [minimum]. Compatibility means the
 * major versions must match and the contract version is greater than or equal to the minimum.
 */
fun ModuleContract.requireCompatible(
    consumer: String,
    minimum: ModuleVersion,
) {
    val actual = version
    if (actual.major != minimum.major || actual < minimum) {
        throw ModuleContractViolationException(api, owner, actual, minimum, consumer)
    }
}

