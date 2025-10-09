package com.novapdf.reader.coroutines

import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher

/**
 * Abstraction over the coroutine dispatchers used throughout the app. This allows tests to
 * substitute deterministic schedulers while the production code continues to rely on the standard
 * [Dispatchers] implementation.
 */
interface CoroutineDispatchers {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: MainCoroutineDispatcher
}

class DefaultCoroutineDispatchers @Inject constructor() : CoroutineDispatchers {
    override val io: CoroutineDispatcher get() = Dispatchers.IO
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val main: MainCoroutineDispatcher get() = Dispatchers.Main
}

class TestCoroutineDispatchers(
    override val io: CoroutineDispatcher,
    override val default: CoroutineDispatcher,
    override val main: MainCoroutineDispatcher,
) : CoroutineDispatchers
