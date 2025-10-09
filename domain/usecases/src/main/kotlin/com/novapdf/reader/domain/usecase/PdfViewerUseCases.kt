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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain-level contract that aggregates the use cases the presentation layer needs.
 */
interface PdfViewerUseCases {
    val document: PdfDocumentUseCase
    val annotations: AnnotationUseCase
    val bookmarks: BookmarkUseCase
    val search: DocumentSearchUseCase
    val remoteDocuments: RemoteDocumentUseCase
    val maintenance: DocumentMaintenanceUseCase
    val crashReporting: CrashReportingUseCase
    val adaptiveFlow: AdaptiveFlowUseCase
}

@Singleton
class DefaultPdfViewerUseCases @Inject constructor(
    override val document: PdfDocumentUseCase,
    override val annotations: AnnotationUseCase,
    override val bookmarks: BookmarkUseCase,
    override val search: DocumentSearchUseCase,
    override val remoteDocuments: RemoteDocumentUseCase,
    override val maintenance: DocumentMaintenanceUseCase,
    override val crashReporting: CrashReportingUseCase,
    override val adaptiveFlow: AdaptiveFlowUseCase,
) : PdfViewerUseCases

interface PdfDocumentUseCase {
    val session: StateFlow<PdfDocumentSession?>
    val outline: StateFlow<List<PdfOutlineNode>>
    val renderProgress: StateFlow<PdfRenderProgress>

    suspend fun open(uri: Uri): PdfDocumentSession
    fun prefetchPages(indices: List<Int>, targetWidth: Int)
    suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap?
    suspend fun renderTile(pageIndex: Int, tileRect: Rect, scale: Float): Bitmap?
    suspend fun getPageSize(pageIndex: Int): Size?
    fun createPrintAdapter(context: Context): PrintDocumentAdapter?
    fun dispose()
}

@Singleton
class DefaultPdfDocumentUseCase @Inject constructor(
    private val repository: PdfDocumentRepository,
) : PdfDocumentUseCase {
    override val session: StateFlow<PdfDocumentSession?>
        get() = repository.session
    override val outline: StateFlow<List<PdfOutlineNode>>
        get() = repository.outline
    override val renderProgress: StateFlow<PdfRenderProgress>
        get() = repository.renderProgress

    override suspend fun open(uri: Uri): PdfDocumentSession = repository.open(uri)

    override fun prefetchPages(indices: List<Int>, targetWidth: Int) {
        repository.prefetchPages(indices, targetWidth)
    }

    override suspend fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? =
        repository.renderPage(pageIndex, targetWidth)

    override suspend fun renderTile(pageIndex: Int, tileRect: Rect, scale: Float): Bitmap? =
        repository.renderTile(pageIndex, tileRect, scale)

    override suspend fun getPageSize(pageIndex: Int): Size? = repository.getPageSize(pageIndex)

    override fun createPrintAdapter(context: Context): PrintDocumentAdapter? =
        repository.createPrintAdapter(context)

    override fun dispose() {
        repository.dispose()
    }
}

interface AnnotationUseCase {
    fun annotationsFor(documentId: String): List<AnnotationCommand>
    fun addAnnotation(documentId: String, annotation: AnnotationCommand)
    fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>)
    fun clear(documentId: String)
    suspend fun persist(documentId: String): File?
    fun trackedDocumentIds(): Set<String>
}

@Singleton
class DefaultAnnotationUseCase @Inject constructor(
    private val repository: AnnotationRepository,
) : AnnotationUseCase {
    override fun annotationsFor(documentId: String): List<AnnotationCommand> =
        repository.annotationsForDocument(documentId)

    override fun addAnnotation(documentId: String, annotation: AnnotationCommand) {
        repository.addAnnotation(documentId, annotation)
    }

    override fun replaceAnnotations(documentId: String, annotations: List<AnnotationCommand>) {
        repository.replaceAnnotations(documentId, annotations)
    }

    override fun clear(documentId: String) {
        repository.clearInMemory(documentId)
    }

    override suspend fun persist(documentId: String): File? = repository.saveAnnotations(documentId)

    override fun trackedDocumentIds(): Set<String> = repository.trackedDocumentIds()
}

interface BookmarkUseCase {
    suspend fun bookmarksFor(documentId: String): List<Int>
    suspend fun toggle(documentId: String, pageIndex: Int)
}

@Singleton
class DefaultBookmarkUseCase @Inject constructor(
    private val bookmarkManager: BookmarkManager,
) : BookmarkUseCase {
    override suspend fun bookmarksFor(documentId: String): List<Int> =
        bookmarkManager.bookmarks(documentId)

    override suspend fun toggle(documentId: String, pageIndex: Int) {
        bookmarkManager.toggleBookmark(documentId, pageIndex)
    }
}

interface DocumentSearchUseCase {
    fun prepare(session: PdfDocumentSession)
    suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult>
    fun dispose()
}

@Singleton
class DefaultDocumentSearchUseCase @Inject constructor(
    private val coordinator: DocumentSearchCoordinator,
) : DocumentSearchUseCase {
    override fun prepare(session: PdfDocumentSession) {
        coordinator.prepare(session)
    }

    override suspend fun search(session: PdfDocumentSession, query: String): List<SearchResult> =
        coordinator.search(session, query)

    override fun dispose() {
        coordinator.dispose()
    }
}

interface RemoteDocumentUseCase {
    suspend fun download(url: String): Result<Uri>
}

@Singleton
class DefaultRemoteDocumentUseCase @Inject constructor(
    private val downloader: RemotePdfDownloader,
) : RemoteDocumentUseCase {
    override suspend fun download(url: String): Result<Uri> = downloader.download(url)
}

interface DocumentMaintenanceUseCase {
    fun scheduleAutosave(documentId: String)
    fun requestImmediateSync(documentId: String)
}

@Singleton
class DefaultDocumentMaintenanceUseCase @Inject constructor(
    private val scheduler: DocumentMaintenanceScheduler,
) : DocumentMaintenanceUseCase {
    override fun scheduleAutosave(documentId: String) {
        scheduler.scheduleAutosave(documentId)
    }

    override fun requestImmediateSync(documentId: String) {
        scheduler.requestImmediateSync(documentId)
    }
}

interface CrashReportingUseCase {
    fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>)
    fun logBreadcrumb(message: String)
}

@Singleton
class DefaultCrashReportingUseCase @Inject constructor(
    private val crashReporter: CrashReporter,
) : CrashReportingUseCase {
    override fun recordNonFatal(throwable: Throwable, metadata: Map<String, String>) {
        crashReporter.recordNonFatal(throwable, metadata)
    }

    override fun logBreadcrumb(message: String) {
        crashReporter.logBreadcrumb(message)
    }
}

interface AdaptiveFlowUseCase {
    val readingSpeed: StateFlow<Float>
    val swipeSensitivity: StateFlow<Float>
    val preloadTargets: StateFlow<List<Int>>
    val uiUnderLoad: StateFlow<Boolean>

    fun start()
    fun stop()
    fun trackPageChange(pageIndex: Int, totalPages: Int)
    fun isUiUnderLoad(): Boolean
}

@Singleton
class DefaultAdaptiveFlowUseCase @Inject constructor(
    private val manager: AdaptiveFlowManager,
) : AdaptiveFlowUseCase {
    override val readingSpeed: StateFlow<Float>
        get() = manager.readingSpeedPagesPerMinute
    override val swipeSensitivity: StateFlow<Float>
        get() = manager.swipeSensitivity
    override val preloadTargets: StateFlow<List<Int>>
        get() = manager.preloadTargets
    override val uiUnderLoad: StateFlow<Boolean>
        get() = manager.uiUnderLoad

    override fun start() = manager.start()
    override fun stop() = manager.stop()
    override fun trackPageChange(pageIndex: Int, totalPages: Int) =
        manager.trackPageChange(pageIndex, totalPages)

    override fun isUiUnderLoad(): Boolean = manager.isUiUnderLoad()
}
