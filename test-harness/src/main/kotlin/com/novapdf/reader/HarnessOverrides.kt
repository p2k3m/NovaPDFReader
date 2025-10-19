package com.novapdf.reader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.novapdf.reader.data.remote.StorageClient
import com.novapdf.reader.model.DocumentSource

/**
 * Describes a document that the harness should open. The document can either be a pre-generated
 * [Uri] on disk or a remote [DocumentSource] that must be resolved by the application layer.
 */
sealed class HarnessDocument {
    data class Local(val uri: Uri) : HarnessDocument()
    data class Remote(val source: DocumentSource) : HarnessDocument()
}

/** Creates a document description for harness runs. */
fun interface HarnessDocumentFactory {
    fun create(context: Context): HarnessDocument?
}

/**
 * Creates a [StorageClient] for harness runs. Implementations may wrap the provided [delegates]
 * or replace them entirely.
 */
fun interface HarnessStorageClientFactory {
    fun create(context: Context, delegates: List<StorageClient>): StorageClient
}

/** Loads harness override factories from instrumentation arguments or environment variables. */
object HarnessOverrideLoader {
    private const val TAG = "HarnessOverrides"

    const val DOCUMENT_FACTORY_ARGUMENT = "harnessDocumentFactory"
    const val DOCUMENT_FACTORY_ENV = "NOVAPDF_HARNESS_DOCUMENT_FACTORY"
    const val STORAGE_FACTORY_ARGUMENT = "harnessStorageClientFactory"
    const val STORAGE_FACTORY_ENV = "NOVAPDF_HARNESS_STORAGE_CLIENT_FACTORY"

    fun loadDocumentFactory(
        context: Context,
        arguments: Bundle?,
    ): HarnessDocumentFactory? {
        val className = resolveClassName(arguments, DOCUMENT_FACTORY_ARGUMENT, DOCUMENT_FACTORY_ENV)
            ?: return null
        return instantiate(context, className, HarnessDocumentFactory::class.java)
    }

    fun loadStorageClientFactory(
        context: Context,
        arguments: Bundle?,
    ): HarnessStorageClientFactory? {
        val className = resolveClassName(arguments, STORAGE_FACTORY_ARGUMENT, STORAGE_FACTORY_ENV)
            ?: return null
        return instantiate(context, className, HarnessStorageClientFactory::class.java)
    }

    private fun resolveClassName(arguments: Bundle?, argumentKey: String, envKey: String): String? {
        val candidates = sequenceOf(
            arguments?.getString(argumentKey),
            arguments?.getString(envKey),
            System.getenv(argumentKey),
            System.getenv(envKey),
        )
        return candidates
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> instantiate(context: Context, className: String, type: Class<T>): T? {
        return runCatching {
            val loader = context.classLoader
            val clazz = Class.forName(className, false, loader)
            if (!type.isAssignableFrom(clazz)) {
                Log.w(TAG, "Ignoring $className because it does not implement ${type.name}")
                return null
            }
            val constructor = clazz.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance() as T
        }.onFailure { error ->
            Log.w(TAG, "Unable to instantiate harness override factory $className", error)
        }.getOrNull()
    }
}
