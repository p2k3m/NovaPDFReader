package com.novapdf.reader

import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.pdf.engine.AdaptiveFlowManager
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.search.LuceneSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler

class TestPdfApp : NovaPdfApp() {
    override fun onCreate() {
        // Skip default initialization so tests can inject dependencies explicitly.
    }

    fun installDependencies(
        annotationRepository: AnnotationRepository,
        pdfRepository: PdfDocumentRepository,
        adaptiveFlowManager: AdaptiveFlowManager,
        bookmarkManager: BookmarkManager,
        documentMaintenanceScheduler: DocumentMaintenanceScheduler,
        searchCoordinator: LuceneSearchCoordinator,
        pdfDownloadManager: PdfDownloadManager,
        crashReporter: CrashReporter = object : CrashReporter {
            override fun install() = Unit
            override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) = Unit
            override fun logBreadcrumb(message: String) = Unit
        },
    ) {
        setField("annotationRepository", annotationRepository)
        setField("pdfDocumentRepository", pdfRepository)
        setField("adaptiveFlowManager", adaptiveFlowManager)
        setField("bookmarkManager", bookmarkManager)
        setField("documentMaintenanceScheduler", documentMaintenanceScheduler)
        setField("searchCoordinator", searchCoordinator)
        setField("pdfDownloadManager", pdfDownloadManager)
        setField("crashReporter", crashReporter)
    }

    private fun setField(name: String, value: Any) {
        val field = NovaPdfApp::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(this, value)
    }

    companion object {
        fun getInstance(): TestPdfApp {
            return ApplicationProvider.getApplicationContext()
        }
    }
}
