package com.novapdf.reader.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.CancellationSignal
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
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job

/**
 * Domain-level contract that aggregates the use cases the presentation layer needs.
 */
interface PdfViewerUseCases {
    val document: PdfDocumentUseCase
    val openDocument: OpenDocumentUseCase
    val renderPage: RenderPageUseCase
    val renderTile: RenderTileUseCase
    val annotations: AnnotationUseCase
    val bookmarks: BookmarkUseCase
    val search: DocumentSearchUseCase
    val buildSearchIndex: BuildSearchIndexUseCase
    val remoteDocuments: RemoteDocumentUseCase
    val maintenance: DocumentMaintenanceUseCase
    val crashReporting: CrashReportingUseCase
    val adaptiveFlow: AdaptiveFlowUseCase
}

@Singleton
class DefaultPdfViewerUseCases @Inject constructor(
    override val document: PdfDocumentUseCase,
    override val openDocument: OpenDocumentUseCase,
    override val renderPage: RenderPageUseCase,
    override val renderTile: RenderTileUseCase,
    override val annotations: AnnotationUseCase,
    override val bookmarks: BookmarkUseCase,
    override val search: DocumentSearchUseCase,
    override val buildSearchIndex: BuildSearchIndexUseCase,
    override val remoteDocuments: RemoteDocumentUseCase,
    override val maintenance: DocumentMaintenanceUseCase,
    override val crashReporting: CrashReportingUseCase,
    override val adaptiveFlow: AdaptiveFlowUseCase,
) : PdfViewerUseCases

interface PdfDocumentUseCase {
    val session: StateFlow<PdfDocumentSession?>
    val outline: StateFlow<List<PdfOutlineNode>>
    val renderProgress: StateFlow<PdfRenderProgress>
    val bitmapMemory: StateFlow<BitmapMemoryStats>

    fun prefetchPages(indices: List<Int>, targetWidth: Int)
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
    override val bitmapMemory: StateFlow<BitmapMemoryStats>
        get() = repository.bitmapMemory

    override fun prefetchPages(indices: List<Int>, targetWidth: Int) {
        repository.prefetchPages(indices, targetWidth)
    }

    override suspend fun getPageSize(pageIndex: Int): Size? = repository.getPageSize(pageIndex)

    override fun createPrintAdapter(context: Context): PrintDocumentAdapter? =
        repository.createPrintAdapter(context)

    override fun dispose() {
        repository.dispose()
    }
}

data class OpenDocumentRequest(val uri: Uri)

data class OpenDocumentResult(val session: PdfDocumentSession)

interface OpenDocumentUseCase {
    suspend operator fun invoke(
        request: OpenDocumentRequest,
        cancellationSignal: CancellationSignal? = null,
    ): Result<OpenDocumentResult>
}

@Singleton
class DefaultOpenDocumentUseCase @Inject constructor(
    private val repository: PdfDocumentRepository,
) : OpenDocumentUseCase {
    override suspend fun invoke(
        request: OpenDocumentRequest,
        cancellationSignal: CancellationSignal?,
    ): Result<OpenDocumentResult> =
        withLinkedCancellation(cancellationSignal) { signal ->
            repository.open(request.uri, signal)
        }.map { session ->
            OpenDocumentResult(session)
        }
}

data class RenderPageRequest(
    val pageIndex: Int,
    val targetWidth: Int,
    val profile: PageRenderProfile = PageRenderProfile.HIGH_DETAIL,
)

data class RenderPageResult(val bitmap: Bitmap?)

interface RenderPageUseCase {
    suspend operator fun invoke(
        request: RenderPageRequest,
        cancellationSignal: CancellationSignal? = null,
    ): Result<RenderPageResult>
}

@Singleton
class DefaultRenderPageUseCase @Inject constructor(
    private val repository: PdfDocumentRepository,
) : RenderPageUseCase {
    override suspend fun invoke(
        request: RenderPageRequest,
        cancellationSignal: CancellationSignal?,
    ): Result<RenderPageResult> =
        withLinkedCancellation(cancellationSignal) { signal ->
            repository.renderPage(request.pageIndex, request.targetWidth, request.profile, signal)
        }.map { bitmap ->
            RenderPageResult(bitmap)
        }
}

data class RenderTileRequest(val pageIndex: Int, val tileRect: Rect, val scale: Float)

data class RenderTileResult(val bitmap: Bitmap?)

interface RenderTileUseCase {
    suspend operator fun invoke(
        request: RenderTileRequest,
        cancellationSignal: CancellationSignal? = null,
    ): Result<RenderTileResult>
}

@Singleton
class DefaultRenderTileUseCase @Inject constructor(
    private val repository: PdfDocumentRepository,
) : RenderTileUseCase {
    override suspend fun invoke(
        request: RenderTileRequest,
        cancellationSignal: CancellationSignal?,
    ): Result<RenderTileResult> =
        withLinkedCancellation(cancellationSignal) { signal ->
            repository.renderTile(request.pageIndex, request.tileRect, request.scale, signal)
        }.map { bitmap ->
            RenderTileResult(bitmap)
        }
}

data class BuildSearchIndexRequest(val session: PdfDocumentSession)

data class BuildSearchIndexResult(val job: Job?)

interface BuildSearchIndexUseCase {
    suspend operator fun invoke(
        request: BuildSearchIndexRequest,
        cancellationSignal: CancellationSignal? = null,
    ): Result<BuildSearchIndexResult>
}

@Singleton
class DefaultBuildSearchIndexUseCase @Inject constructor(
    private val coordinator: DocumentSearchCoordinator,
) : BuildSearchIndexUseCase {
    override suspend fun invoke(
        request: BuildSearchIndexRequest,
        cancellationSignal: CancellationSignal?,
    ): Result<BuildSearchIndexResult> = runCatching {
        val job = coordinator.prepare(request.session)
        if (job == null) {
            BuildSearchIndexResult(job = null)
        } else {
            val cancelException = CancellationException("Search indexing cancelled")
            cancellationSignal?.let { signal ->
                if (signal.isCanceled) {
                    job.cancel(cancelException)
                } else {
                    signal.setOnCancelListener {
                        job.cancel(cancelException)
                    }
                    job.invokeOnCompletion { signal.setOnCancelListener(null) }
                }
            }
            BuildSearchIndexResult(job = job)
        }
    }
}

private suspend fun <T> withLinkedCancellation(
    signal: CancellationSignal?,
    onCancel: (() -> Unit)? = null,
    block: suspend (CancellationSignal?) -> T,
): Result<T> {
    if (signal == null) {
        return runCatching { block(null) }
    }
    if (signal.isCanceled) {
        return Result.failure(CancellationException("Operation cancelled"))
    }
    val job = coroutineContext.job
    val listener = CancellationSignal.OnCancelListener {
        onCancel?.invoke()
        job.cancel(CancellationException("Operation cancelled by caller"))
    }
    signal.setOnCancelListener(listener)
    val detachHandle: DisposableHandle = job.invokeOnCompletion { cause ->
        signal.setOnCancelListener(null)
        if (cause is CancellationException && !signal.isCanceled) {
            signal.cancel()
        }
    }
    return try {
        Result.success(block(signal))
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    } finally {
        detachHandle.dispose()
        signal.setOnCancelListener(null)
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
    val indexingState: StateFlow<SearchIndexingState>
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

    override val indexingState: StateFlow<SearchIndexingState>
        get() = coordinator.indexingState
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
