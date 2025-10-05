package com.novapdf.reader.search

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object PdfBoxInitializer {
    private val initialized = AtomicBoolean(false)
    private val mutex = Mutex()

    suspend fun ensureInitialized(context: Context): Boolean {
        if (initialized.get()) return true
        return mutex.withLock {
            if (initialized.get()) {
                return@withLock true
            }
            val success = try {
                withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }
                true
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to initialise PDFBox resources", error)
                false
            }
            if (success) {
                initialized.set(true)
            }
            success
        }
    }

    private const val TAG = "PdfBoxInitializer"
}
