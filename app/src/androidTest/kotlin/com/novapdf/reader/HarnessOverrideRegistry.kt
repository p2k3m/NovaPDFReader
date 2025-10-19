package com.novapdf.reader

import android.content.Context
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.data.remote.StorageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HarnessOverrideRegistry @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
) {
    private val arguments = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()

    private val documentFactory = HarnessOverrideLoader.loadDocumentFactory(
        applicationContext,
        arguments,
    )

    private val storageFactory = HarnessOverrideLoader.loadStorageClientFactory(
        applicationContext,
        arguments,
    )

    fun prepareDocument(context: Context): HarnessDocument? {
        val factory = documentFactory ?: return null
        return runCatching { factory.create(context) }
            .onFailure { error ->
                Log.w(TAG, "Harness document factory threw an exception", error)
            }
            .getOrNull()
    }

    fun storageClientOverride(
        context: Context,
        delegates: List<StorageClient>,
    ): StorageClient? {
        val factory = storageFactory ?: return null
        return runCatching { factory.create(context, delegates) }
            .onFailure { error ->
                Log.w(TAG, "Harness storage client factory threw an exception", error)
            }
            .getOrNull()
    }

    companion object {
        private const val TAG = "HarnessOverrides"
    }
}
