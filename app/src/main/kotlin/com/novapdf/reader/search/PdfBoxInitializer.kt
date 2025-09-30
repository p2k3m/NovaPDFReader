package com.novapdf.reader.search

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object PdfBoxInitializer {
    private val initialized = AtomicBoolean(false)
    private val mutex = Mutex()

    suspend fun ensureInitialized(context: Context) {
        if (initialized.get()) return
        mutex.withLock {
            if (!initialized.get()) {
                withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }
                initialized.set(true)
            }
        }
    }
}
