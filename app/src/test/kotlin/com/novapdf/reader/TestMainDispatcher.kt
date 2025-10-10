package com.novapdf.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.CoroutineContext

internal fun CoroutineDispatcher.asTestMainDispatcher(): MainCoroutineDispatcher =
    when (this) {
        is MainCoroutineDispatcher -> this
        else -> @OptIn(InternalCoroutinesApi::class)
        object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                this@asTestMainDispatcher.dispatch(context, block)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun dispatchYield(context: CoroutineContext, block: Runnable) {
                this@asTestMainDispatcher.dispatchYield(context, block)
            }

            override fun isDispatchNeeded(context: CoroutineContext): Boolean =
                this@asTestMainDispatcher.isDispatchNeeded(context)

            @Suppress("OVERRIDE_DEPRECATION")
            override fun limitedParallelism(parallelism: Int): CoroutineDispatcher =
                this@asTestMainDispatcher.limitedParallelism(parallelism)

            override fun toString(): String = this@asTestMainDispatcher.toString()
        }
    }
