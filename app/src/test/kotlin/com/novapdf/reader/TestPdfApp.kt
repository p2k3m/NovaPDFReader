package com.novapdf.reader

import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.domain.usecase.AdaptiveFlowUseCase
import com.novapdf.reader.domain.usecase.AnnotationUseCase
import com.novapdf.reader.domain.usecase.BookmarkUseCase
import com.novapdf.reader.domain.usecase.CrashReportingUseCase
import com.novapdf.reader.domain.usecase.DocumentMaintenanceUseCase
import com.novapdf.reader.domain.usecase.DocumentSearchUseCase
import com.novapdf.reader.domain.usecase.PdfDocumentUseCase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.domain.usecase.RemoteDocumentUseCase
import com.novapdf.reader.download.S3RemotePdfDownloader
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.search.DocumentSearchCoordinator
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
        searchCoordinator: DocumentSearchCoordinator,
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
        setField(
            "pdfViewerUseCases\$delegate",
            lazyOf(
                PdfViewerUseCases(
                    document = PdfDocumentUseCase(pdfRepository),
                    annotations = AnnotationUseCase(annotationRepository),
                    bookmarks = BookmarkUseCase(bookmarkManager),
                    search = DocumentSearchUseCase(searchCoordinator),
                    remoteDocuments = RemoteDocumentUseCase(S3RemotePdfDownloader(pdfDownloadManager)),
                    maintenance = DocumentMaintenanceUseCase(documentMaintenanceScheduler),
                    crashReporting = CrashReportingUseCase(crashReporter),
                    adaptiveFlow = AdaptiveFlowUseCase(adaptiveFlowManager)
                )
            )
        )
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
