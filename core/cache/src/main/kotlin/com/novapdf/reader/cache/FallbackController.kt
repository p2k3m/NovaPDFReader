package com.novapdf.reader.cache

import java.util.Collections
import java.util.LinkedHashSet

/**
 * Tracks fault conditions for a component that can operate in a "fallback" mode. The controller
 * guarantees that activations are idempotent and that deactivation callbacks only run when the last
 * active reason is cleared. This makes it safe to wire into caches, storage engines, or downloaders
 * without bespoke bookkeeping in each call site.
 */
class FallbackController(
    private val onActivated: (reason: String) -> Unit,
    private val onDeactivated: () -> Unit = {},
    private val onReasonWhileActive: ((reason: String) -> Unit)? = null,
) {

    private val reasons: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Returns `true` when at least one fallback reason is currently active. */
    val isActive: Boolean
        get() = reasons.isNotEmpty()

    /** Returns a snapshot of the active fallback reasons. */
    fun reasons(): Set<String> = synchronized(reasons) { LinkedHashSet(reasons) }

    /** Activates fallback for the provided [reason]. */
    fun activate(reason: String) {
        if (reason.isEmpty()) return
        val shouldNotify = synchronized(reasons) {
            if (reasons.add(reason)) {
                reasons.size == 1
            } else {
                false
            }
        }
        if (shouldNotify) {
            onActivated(reason)
        } else {
            onReasonWhileActive?.invoke(reason)
        }
    }

    /** Clears the given [reason]. */
    fun deactivate(reason: String) {
        val shouldNotify = synchronized(reasons) {
            reasons.remove(reason)
            reasons.isEmpty()
        }
        if (shouldNotify) {
            onDeactivated()
        }
    }

    /** Clears all currently active reasons and invokes [onDeactivated] if needed. */
    fun clear() {
        val shouldNotify = synchronized(reasons) {
            if (reasons.isEmpty()) {
                false
            } else {
                reasons.clear()
                true
            }
        }
        if (shouldNotify) {
            onDeactivated()
        }
    }
}
