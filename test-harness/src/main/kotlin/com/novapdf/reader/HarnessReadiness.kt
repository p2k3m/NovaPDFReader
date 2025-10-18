package com.novapdf.reader

/**
 * Signals when the screenshot harness test process has completed its dependency injection and
 * reached an idle state, allowing external tooling to synchronise on a simple console marker.
 */
object HarnessReadiness {
    const val READINESS_MARKER: String = "I AM READY"

    /**
     * Emits the readiness marker. The [consumer] receives the marker so callers can decide how it
     * should be surfaced (for example, plain `println`).
     */
    fun emit(consumer: (String) -> Unit) {
        consumer(READINESS_MARKER)
    }
}
