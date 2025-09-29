package com.novapdf.reader

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

open class NovaPdfApp : Application() {
    lateinit var annotationRepository: AnnotationRepository
        private set
    lateinit var pdfDocumentRepository: PdfDocumentRepository
        private set
    lateinit var adaptiveFlowManager: AdaptiveFlowManager
        private set
    lateinit var bookmarkManager: BookmarkManager
        private set
    lateinit var database: NovaPdfDatabase
        private set
    lateinit var documentMaintenanceScheduler: DocumentMaintenanceScheduler
        private set
    lateinit var searchCoordinator: LuceneSearchCoordinator
        private set
    lateinit var pdfDownloadManager: PdfDownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        annotationRepository = AnnotationRepository(this)
        pdfDocumentRepository = PdfDocumentRepository(this)
        searchCoordinator = LuceneSearchCoordinator(this, pdfDocumentRepository)
        adaptiveFlowManager = AdaptiveFlowManager(this)
        pdfDownloadManager = PdfDownloadManager(this)
        database = Room.databaseBuilder(
            applicationContext,
            NovaPdfDatabase::class.java,
            NovaPdfDatabase.NAME
        ).fallbackToDestructiveMigration().build()
        bookmarkManager = BookmarkManager(
            database.bookmarkDao(),
            getSharedPreferences(BookmarkManager.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
        )
        documentMaintenanceScheduler = DocumentMaintenanceScheduler(this)
        documentMaintenanceScheduler.ensurePeriodicSync()
    }

    override fun onTerminate() {
        super.onTerminate()
        if (this::searchCoordinator.isInitialized) {
            searchCoordinator.dispose()
        }
    }
}
