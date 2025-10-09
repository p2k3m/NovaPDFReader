package com.novapdf.reader.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Coordinates adaptive reading heuristics and exposes observable state so that
 * presentation layers can react without depending on a specific engine
 * implementation.
 */
interface AdaptiveFlowManager {
    val swipeSensitivity: StateFlow<Float>
    val preloadTargets: StateFlow<List<Int>>
    val uiUnderLoad: StateFlow<Boolean>
    val readingSpeedPagesPerMinute: StateFlow<Float>
    val frameIntervalMillis: StateFlow<Float>

    fun start()
    fun stop()
    fun trackPageChange(pageIndex: Int, totalPages: Int)
    fun isUiUnderLoad(): Boolean

    /** Allows tests to control swipe sensitivity without physical sensors. */
    fun overrideSensitivityForTesting(value: Float)
}
