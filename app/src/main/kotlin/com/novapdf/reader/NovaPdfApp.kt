package com.novapdf.reader

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.search.PdfBoxInitializer
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidApp
open class NovaPdfApp : Application(), Configuration.Provider {

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var adaptiveFlowManager: AdaptiveFlowManager

    @Inject
    lateinit var pdfViewerUseCases: PdfViewerUseCases

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var documentMaintenanceSchedulerProvider: Provider<DocumentMaintenanceScheduler>

    @Inject
    lateinit var searchCoordinatorProvider: Provider<DocumentSearchCoordinator>

    @Inject
    lateinit var bookmarkManagerProvider: Provider<BookmarkManager>

    @Inject
    lateinit var databaseProvider: Provider<NovaPdfDatabase>

    private val scopeExceptionHandler = CoroutineExceptionHandler { _, error ->
        logDeferredInitializationFailure("uncaught", error)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeExceptionHandler)
    private val backgroundInitializationStarted = AtomicBoolean(false)

    private var searchCoordinatorInstance: DocumentSearchCoordinator? = null
    private var bookmarkManagerInstance: BookmarkManager? = null
    private var maintenanceSchedulerInstance: DocumentMaintenanceScheduler? = null

    private fun searchCoordinator(): DocumentSearchCoordinator {
        val existing = searchCoordinatorInstance
        if (existing != null) return existing
        return searchCoordinatorProvider.get().also { searchCoordinatorInstance = it }
    }

    private fun bookmarkManager(): BookmarkManager {
        val existing = bookmarkManagerInstance
        if (existing != null) return existing
        return bookmarkManagerProvider.get().also { bookmarkManagerInstance = it }
    }

    private fun documentMaintenanceScheduler(): DocumentMaintenanceScheduler {
        val existing = maintenanceSchedulerInstance
        if (existing != null) return existing
        return documentMaintenanceSchedulerProvider.get().also { maintenanceSchedulerInstance = it }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyDeath()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyDeath()
                    .build()
            )
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        searchCoordinatorInstance?.dispose()
    }

    internal fun beginStartupInitialization() {
        if (!backgroundInitializationStarted.compareAndSet(false, true)) {
            return
        }

        applicationScope.launch {
            initializeDeferredComponents()
        }

        ensurePeriodicMaintenanceConfigured()
    }

    private suspend fun initializeDeferredComponents() {
        val databaseReady = withContext(Dispatchers.IO) {
            runCatching { databaseProvider.get() }
                .onFailure { error -> logDeferredInitializationFailure("database", error) }
                .isSuccess
        }
        if (databaseReady) {
            withContext(Dispatchers.IO) {
                runCatching { bookmarkManager() }
                    .onFailure { error -> logDeferredInitializationFailure("bookmarks", error) }
            }
        }
        val pdfBoxReady = runCatching { PdfBoxInitializer.ensureInitialized(applicationContext) }
            .onFailure { error -> logDeferredInitializationFailure("pdfbox", error) }
            .getOrDefault(false)
        if (!pdfBoxReady) {
            logDeferredInitializationWarning(
                "pdfbox",
                "PDFBox resources failed to initialise; search indexing will be disabled"
            )
        }
    }

    private fun ensurePeriodicMaintenanceConfigured() {
        runCatching { documentMaintenanceScheduler() }
            .onFailure { error -> logDeferredInitializationFailure("maintenance", error) }
    }

    private fun logDeferredInitializationFailure(stage: String, error: Throwable) {
        Log.w(TAG, "Deferred initialisation failed for $stage", error)
        crashReporter.recordNonFatal(error, mapOf("stage" to stage))
    }

    private fun logDeferredInitializationWarning(stage: String, message: String) {
        Log.w(TAG, message)
        crashReporter.logBreadcrumb("$stage: $message")
    }

    companion object {
        private const val TAG = "NovaPdfApp"
    }
}
