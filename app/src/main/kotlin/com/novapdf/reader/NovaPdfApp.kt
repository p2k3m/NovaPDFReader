package com.novapdf.reader

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.util.Log
import androidx.room.Room
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.download.S3RemotePdfDownloader
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.logging.FileCrashReporter
import com.novapdf.reader.pdf.engine.DefaultAdaptiveFlowManager
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.search.PdfBoxInitializer
import com.novapdf.reader.work.DocumentMaintenanceDependencies
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.novapdf.reader.domain.usecase.AdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.AnnotationUseCase
import com.novapdf.reader.domain.usecase.BookmarkUseCase
import com.novapdf.reader.domain.usecase.CrashReportingUseCase
import com.novapdf.reader.domain.usecase.DocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DocumentSearchUseCase
import com.novapdf.reader.domain.usecase.PdfDocumentUseCase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RemoteDocumentUseCase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

open class NovaPdfApp : Application(), DocumentMaintenanceDependencies, NovaPdfDependencies {
    private val scopeExceptionHandler = CoroutineExceptionHandler { _, error ->
        logDeferredInitializationFailure("uncaught", error)
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeExceptionHandler)
    private val backgroundInitializationStarted = AtomicBoolean(false)

    val crashReporter: CrashReporter by lazy {
        FileCrashReporter(this).also { it.install() }
    }

    override val annotationRepository: AnnotationRepository by lazy { AnnotationRepository(this) }

    val pdfDocumentRepository: PdfDocumentRepository by lazy {
        PdfDocumentRepository(this, crashReporter = crashReporter)
    }

    private val searchCoordinatorDelegate = lazy<DocumentSearchCoordinator> {
        LuceneSearchCoordinator(this, pdfDocumentRepository)
    }
    val searchCoordinator: DocumentSearchCoordinator by searchCoordinatorDelegate

    val adaptiveFlowManager: AdaptiveFlowManager by lazy { DefaultAdaptiveFlowManager(this) }

    val pdfDownloadManager: PdfDownloadManager by lazy { PdfDownloadManager(this) }

    private val databaseDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Room.databaseBuilder(
            applicationContext,
            NovaPdfDatabase::class.java,
            NovaPdfDatabase.NAME
        ).fallbackToDestructiveMigration().build()
    }
    val database: NovaPdfDatabase
        get() = databaseDelegate.value

    private val bookmarkManagerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookmarkManager(
            database.bookmarkDao(),
            getSharedPreferences(BookmarkManager.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
    }
    override val bookmarkManager: BookmarkManager
        get() = bookmarkManagerDelegate.value

    private val maintenanceSchedulerDelegate = lazy {
        DocumentMaintenanceScheduler(this).also { it.ensurePeriodicSync() }
    }
    val documentMaintenanceScheduler: DocumentMaintenanceScheduler by maintenanceSchedulerDelegate

    override val pdfViewerUseCases: PdfViewerUseCases by lazy {
        PdfViewerUseCases(
            document = PdfDocumentUseCase(pdfDocumentRepository),
            annotations = AnnotationUseCase(annotationRepository),
            bookmarks = BookmarkUseCase(bookmarkManager),
            search = DocumentSearchUseCase(searchCoordinator),
            remoteDocuments = RemoteDocumentUseCase(S3RemotePdfDownloader(pdfDownloadManager)),
            maintenance = DocumentMaintenanceUseCase(documentMaintenanceScheduler),
            crashReporting = CrashReportingUseCase(crashReporter),
            adaptiveFlow = AdaptiveFlowUseCase(adaptiveFlowManager)
        )
    }

    override fun isUiUnderLoad(): Boolean = adaptiveFlowManager.isUiUnderLoad()

    override fun onCreate() {
        super.onCreate()
        // Install crash handling immediately so background initialisation can report failures.
        crashReporter

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
        if (searchCoordinatorDelegate.isInitialized()) {
            searchCoordinator.dispose()
        }
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
            runCatching { databaseDelegate.value }
                .onFailure { error -> logDeferredInitializationFailure("database", error) }
                .isSuccess
        }
        if (databaseReady) {
            withContext(Dispatchers.IO) {
                runCatching { bookmarkManagerDelegate.value }
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
        runCatching { documentMaintenanceScheduler }
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

    private companion object {
        private const val TAG = "NovaPdfApp"
    }
}
