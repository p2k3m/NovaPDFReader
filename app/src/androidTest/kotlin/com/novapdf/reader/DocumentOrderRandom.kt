package com.novapdf.reader

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.logging.NovaLog
import java.util.Collections
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Provides deterministic but randomized ordering for document-related operations during
 * instrumentation runs. The seed can be overridden via either the instrumentation argument
 * `documentOrderSeed` or the `NOVAPDF_DOCUMENT_ORDER_SEED` environment variable so failures can be
 * reproduced.
 */
internal object DocumentOrderRandom {
    private const val TAG = "DocumentOrderRandom"
    private const val ARGUMENT_KEY = "documentOrderSeed"
    private const val ENVIRONMENT_KEY = "NOVAPDF_DOCUMENT_ORDER_SEED"

    private val seedLogged = AtomicBoolean(false)

    private val baseSeed: Long by lazy { resolveSeed() }

    private fun resolveSeed(): Long {
        val arguments = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()
        val rawSeed = resolveRawSeed(arguments)
        val providedSeed = rawSeed?.toLongOrNull()
        val seed = providedSeed ?: kotlin.random.Random.Default.nextLong()
        logSeed(seed, providedSeed != null)
        return seed
    }

    private fun resolveRawSeed(arguments: Bundle?): String? = sequenceOf(
        arguments?.getString(ARGUMENT_KEY),
        arguments?.getString(ENVIRONMENT_KEY),
        System.getenv(ARGUMENT_KEY),
        System.getenv(ENVIRONMENT_KEY),
    ).mapNotNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull()

    private fun logSeed(seed: Long, provided: Boolean) {
        if (seedLogged.compareAndSet(false, true)) {
            if (provided) {
                NovaLog.i(TAG, "Using provided document order seed $seed")
            } else {
                NovaLog.i(
                    TAG,
                    "Generated document order seed $seed (override with instrumentation " +
                        "argument \"$ARGUMENT_KEY\" or environment variable $ENVIRONMENT_KEY)",
                )
            }
        }
    }

    private fun derivedSeed(context: String): Long {
        var hash = 1125899906842597L
        for (character in context) {
            hash = hash * 131 + character.code
        }
        return baseSeed xor hash
    }

    fun <T> shuffled(context: String, items: List<T>): List<T> {
        if (items.size <= 1) {
            return items
        }
        val mutable = items.toMutableList()
        Collections.shuffle(mutable, Random(derivedSeed(context)))
        return mutable
    }
}
