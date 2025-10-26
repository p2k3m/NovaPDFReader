package com.novapdf.reader.storage

import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlin.LazyThreadSafetyMode

/**
 * Provides helpers for interacting with [PdfiumCore] in a thread-safe manner.
 *
 * Pdfium exposes a single global lock that must guard all JNI entry points. Newer
 * library versions still rely on this lock internally, so callers should honour it
 * whenever documents are opened to avoid racing native state across threads.
 */
object PdfiumCompat {
    private val lockField by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            PdfiumCore::class.java.getDeclaredField("lock").apply { isAccessible = true }
        }.getOrNull()
    }

    /** Shared Pdfium mutex. The value may be null on older binaries. */
    val lock: Any? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching { lockField?.get(null) }.getOrNull()
    }

    /** Executes [block] while holding the Pdfium global lock when it exists. */
    inline fun <T> withLock(block: () -> T): T {
        val mutex = lock
        return if (mutex != null) {
            synchronized(mutex) { block() }
        } else {
            block()
        }
    }

    /** Opens a [PdfDocument] while coordinating access through the Pdfium lock. */
    fun openDocument(core: PdfiumCore, descriptor: ParcelFileDescriptor): PdfDocument {
        return withLock { core.newDocument(descriptor) }
    }
}
