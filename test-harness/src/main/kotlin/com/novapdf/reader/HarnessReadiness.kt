package com.novapdf.reader

import android.app.Instrumentation
import android.os.Bundle

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
        sendInstrumentationStatus(READINESS_MARKER)
    }

    private fun sendInstrumentationStatus(message: String) {
        runCatching {
            val registryClass = Class.forName(
                "androidx.test.platform.app.InstrumentationRegistry"
            )
            val instrumentation = registryClass
                .getMethod("getInstrumentation")
                .invoke(null) as? Instrumentation ?: return
            val bundle = Bundle().apply { putString("stream", message) }
            instrumentation.sendStatus(0, bundle)
        }
    }
}
