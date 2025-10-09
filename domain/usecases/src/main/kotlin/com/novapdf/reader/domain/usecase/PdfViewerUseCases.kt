package com.novapdf.reader.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.print.PrintDocumentAdapter
import android.util.Size
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.download.RemotePdfDownloader
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Aggregates the core domain operations required by the presentation layer when rendering
 * and interacting with documents. Each nested use case isolates a particular set of
 * responsibilities so ViewModels can depend on a cohesive API surface.
 */
data class PdfViewerUseCases(
    val document: PdfDocumentUseCase,
    val annotations: AnnotationUseCase,
    val bookmarks: BookmarkUseCase,
    val search: DocumentSearchUseCase,
    val remoteDocuments: RemoteDocumentUseCase,
    val maintenance: DocumentMaintenanceUseCase,
    val crashReporting: CrashReportingUseCase,
    val adaptiveFlow: AdaptiveFlowUseCase
)

class PdfDocumentUseCase(
    private val repository: PdfDocumentRepository
) {
    val session: StateFlow<PdfDocumentSession?> get() = repository.session
    val outline: StateFlow<List<PdfOutlineNode>> get() = repository.outline
    val renderProgress: StateFlow<PdfRenderProgress> get() = repository.renderProgress

    suspend fun open(uri: Uri): PdfDocumentSession = repository.open(uri)

    fun prefetchPages(indices: List<Int>, targetWidth: Int) {
        repository.prefetchPages(indices, targetWidth)
    }

    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? =
        repository.renderPage(pageIndex, targetWidth)

    suspend fun renderTile(pageIndex: Int, tileRect: Rect, scale: Float): Bitmap? =
        repository.renderTile(pageIndex, tileRect, scale)

    suspend fun getPageSize(pageIndex: Int): Size? = repository.getPageSize(pageIndex)

    fun createPrintAdapter(context: Context): PrintDocumentAdapter? =
        repository.createPrintAdapter(context)

    fun dispose() {
        repository.dispose()
    }
}

class AnnotationUseCase(
    private val repository: AnnotationRepository
) {
    fun annotationsFor(documentId: String): List<AnnotationCommand> =
        repository.annotationsForDocument(documentId)

    fun addAnnotation(documentId: String, annotation: AnnotationCommand) {
        repository.addAnnotation(documentId, annotation)
    }

    fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>) {
        repository.replaceAnnotations(documentId, annotations)
    }

    fun clear(documentId: String) {
        repository.clearInMemory(documentId)
    }

    suspend fun persist(documentId: String): File? = repository.saveAnnotations(documentId)

    fun trackedDocumentIds(): Set<String> = repository.trackedDocumentIds()
}

class BookmarkUseCase(
    private val bookmarkManager: BookmarkManager
) {
    suspend fun bookmarksFor(documentId: String): List<Int> = bookmarkManager.bookmarks(documentId)

    suspend fun toggle(documentId: String, pageIndex: Int) {
        bookmarkManager.toggleBookmark(documentId, pageIndex)
    }
}

class DocumentSearchUseCase(
    private val coordinator: DocumentSearchCoordinator
) {
    fun prepare(session: PdfDocumentSession) {
        coordinator.prepare(session)
    }

    suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult> =
        coordinator.search(session, query)

    fun dispose() {
        coordinator.dispose()
    }
}

class RemoteDocumentUseCase(
    private val downloader: RemotePdfDownloader
) {
    suspend fun download(url: String): Result<Uri> = downloader.download(url)
}

class DocumentMaintenanceUseCase(
    private val scheduler: DocumentMaintenanceScheduler
) {
    fun scheduleAutosave(documentId: String) {
        scheduler.scheduleAutosave(documentId)
    }

    fun requestImmediateSync(documentId: String) {
        scheduler.requestImmediateSync(documentId)
    }
}

class CrashReportingUseCase(
    private val crashReporter: CrashReporter
) {
    fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) {
        crashReporter.recordNonFatal(throwable, metadata)
    }

    fun logBreadcrumb(message: String) {
        crashReporter.logBreadcrumb(message)
    }
}

class AdaptiveFlowUseCase(
    private val manager: AdaptiveFlowManager
) {
    val readingSpeed: StateFlow<Float> get() = manager.readingSpeedPagesPerMinute
    val swipeSensitivity: StateFlow<Float> get() = manager.swipeSensitivity
    val preloadTargets: StateFlow<List<Int>> get() = manager.preloadTargets
    val uiUnderLoad: StateFlow<Boolean> get() = manager.uiUnderLoad

    fun start() = manager.start()
    fun stop() = manager.stop()
    fun trackPageChange(pageIndex: Int, totalPages: Int) = manager.trackPageChange(pageIndex, totalPages)
    fun isUiUnderLoad(): Boolean = manager.isUiUnderLoad()
}
