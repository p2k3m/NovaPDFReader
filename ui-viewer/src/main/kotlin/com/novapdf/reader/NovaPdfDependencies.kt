package com.novapdf.reader

import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.remote.PdfDownloadManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler

interface NovaPdfDependencies {
    val annotationRepository: AnnotationRepository
    val pdfDocumentRepository: PdfDocumentRepository
    val adaptiveFlowManager: AdaptiveFlowManager
    val bookmarkManager: BookmarkManager
    val documentMaintenanceScheduler: DocumentMaintenanceScheduler
    val searchCoordinator: DocumentSearchCoordinator
    val pdfDownloadManager: PdfDownloadManager
    val crashReporter: CrashReporter
}
