package com.novapdf.reader

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.logging.FileCrashReporter
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.search.PdfBoxInitializer
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

open class NovaPdfApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val crashReporter: CrashReporter by lazy {
        FileCrashReporter(this).also { it.install() }
    }

    val annotationRepository: AnnotationRepository by lazy { AnnotationRepository(this) }

    val pdfDocumentRepository: PdfDocumentRepository by lazy {
        PdfDocumentRepository(this, crashReporter = crashReporter)
    }

    private val searchCoordinatorDelegate = lazy { LuceneSearchCoordinator(this, pdfDocumentRepository) }
    val searchCoordinator: LuceneSearchCoordinator by searchCoordinatorDelegate

    val adaptiveFlowManager: AdaptiveFlowManager by lazy { AdaptiveFlowManager(this) }

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
    val bookmarkManager: BookmarkManager
        get() = bookmarkManagerDelegate.value

    private val maintenanceSchedulerDelegate = lazy {
        DocumentMaintenanceScheduler(this).also { it.ensurePeriodicSync() }
    }
    val documentMaintenanceScheduler: DocumentMaintenanceScheduler by maintenanceSchedulerDelegate

    override fun onCreate() {
        super.onCreate()
        // Install crash handling immediately so background initialisation can report failures.
        crashReporter

        // Schedule heavy singletons to initialise off the main thread.
        applicationScope.launch(Dispatchers.IO) {
            databaseDelegate.value
            bookmarkManagerDelegate.value
            PdfBoxInitializer.ensureInitialized(applicationContext)
        }

        // Ensure periodic maintenance is configured.
        documentMaintenanceScheduler
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        if (searchCoordinatorDelegate.isInitialized()) {
            searchCoordinator.dispose()
        }
    }
}
