package com.novapdf.reader.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.print.PrintDocumentAdapter
import android.util.Size
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.PdfDocumentSession
import com.novapdf.reader.data.PdfDocumentRepository
import com.novapdf.reader.data.PdfOpenException
import com.novapdf.reader.data.PdfRenderException
import com.novapdf.reader.data.remote.DocumentSourceGateway
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.data.remote.RemotePdfException
import com.novapdf.reader.data.remote.RemoteSourceDiagnostics
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.model.RemoteDocumentFetchEvent
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.DomainErrorCode
import com.novapdf.reader.model.DomainException
import com.novapdf.reader.model.PageRenderProfile
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingState
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import java.io.File
import java.io.InterruptedIOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val preferences: UserPreferencesUseCase
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
    override val preferences: UserPreferencesUseCase,
    contractRegistry: ModuleContractsRegistry,
) : PdfViewerUseCases {

    init {
        contractRegistry.verifyDomainUseCases()
    }
}

interface PdfDocumentUseCase {
    val session: StateFlow<PdfDocumentSession?>
    val outline: StateFlow<List<PdfOutlineNode>>
    val renderProgress: StateFlow<PdfRenderProgress>
    val bitmapMemory: StateFlow<BitmapMemoryStats>
    val cacheFallbackActive: StateFlow<Boolean>

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
    override val cacheFallbackActive: StateFlow<Boolean>
        get() = repository.cacheFallbackActive

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
    ): Result<BuildSearchIndexResult> = runDomainCatching {
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
        return runDomainCatchingSuspend { block(null) }
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
        when (throwable) {
            is TimeoutCancellationException -> Result.failure(throwable.toDomainException())
            is CancellationException -> throw throwable
            else -> Result.failure(throwable.toDomainException())
        }
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
    fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent>
}

@Singleton
class DefaultRemoteDocumentUseCase @Inject constructor(
    private val gateway: DocumentSourceGateway,
) : RemoteDocumentUseCase {
    private val stateLock = Mutex()
    private var circuitOpen: Boolean = false
    private var consecutiveNetworkFailures: Int = 0
    private var lastNetworkFailure: RemotePdfException? = null
    private var circuitDiagnostics: RemoteSourceDiagnostics? = null

    override fun fetch(source: DocumentSource): Flow<RemoteDocumentFetchEvent> = flow {
        val circuitBreakerFailure = stateLock.withLock { buildCircuitOpenFailureLocked() }
        if (circuitBreakerFailure != null) {
            emit(RemoteDocumentFetchEvent.Failure(circuitBreakerFailure))
            return@flow
        }

        var shouldTerminate = false
        gateway.fetch(source).collect { event ->
            when (event) {
                is RemoteDocumentFetchEvent.Progress -> emit(event)
                is RemoteDocumentFetchEvent.Success -> {
                    stateLock.withLock { resetFailuresLocked() }
                    emit(event)
                    shouldTerminate = true
                    return@collect
                }
                is RemoteDocumentFetchEvent.Failure -> {
                    val error = event.error
                    if (error is CancellationException) throw error
                    val processed = stateLock.withLock { handleFailureLocked(error) }
                    emit(RemoteDocumentFetchEvent.Failure(processed))
                    shouldTerminate = true
                    return@collect
                }
            }
        }
        if (shouldTerminate) {
            return@flow
        }
    }

    private fun buildCircuitOpenFailureLocked(): RemotePdfException? {
        if (!circuitOpen) return null
        return RemotePdfException(
            RemotePdfException.Reason.CIRCUIT_OPEN,
            lastNetworkFailure,
            circuitDiagnostics,
        )
    }

    private fun handleFailureLocked(throwable: Throwable?): RemotePdfException {
        val remoteFailure = when (throwable) {
            is RemotePdfException -> throwable
            null -> RemotePdfException(RemotePdfException.Reason.NETWORK)
            else -> RemotePdfException(RemotePdfException.Reason.NETWORK, throwable)
        }

        when (remoteFailure.reason) {
            RemotePdfException.Reason.NETWORK,
            RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED -> {
                lastNetworkFailure = remoteFailure
                consecutiveNetworkFailures += 1
                if (!circuitOpen && consecutiveNetworkFailures >= CIRCUIT_BREAKER_THRESHOLD) {
                    circuitOpen = true
                    val diagnostics = RemoteSourceDiagnostics(
                        failureCount = consecutiveNetworkFailures,
                        lastFailureReason = remoteFailure.reason,
                        lastFailureMessage = resolveFailureMessage(remoteFailure),
                    )
                    circuitDiagnostics = diagnostics
                    return RemotePdfException(
                        RemotePdfException.Reason.CIRCUIT_OPEN,
                        remoteFailure,
                        diagnostics,
                    )
                }
            }

            RemotePdfException.Reason.CORRUPTED,
            RemotePdfException.Reason.UNSAFE,
            RemotePdfException.Reason.FILE_TOO_LARGE -> {
                resetFailuresLocked()
            }

            RemotePdfException.Reason.CIRCUIT_OPEN -> {
                circuitOpen = true
                remoteFailure.diagnostics?.let { diagnostics ->
                    circuitDiagnostics = diagnostics
                    consecutiveNetworkFailures = diagnostics.failureCount
                }
                lastNetworkFailure = remoteFailure
            }
        }

        return remoteFailure
    }

    private fun resetFailuresLocked() {
        if (circuitOpen) return
        consecutiveNetworkFailures = 0
        lastNetworkFailure = null
        circuitDiagnostics = null
    }

    private fun resolveFailureMessage(throwable: Throwable?): String? {
        var current = throwable
        while (current != null) {
            if (current !is RemotePdfException) {
                val message = current.message
                if (!message.isNullOrBlank()) {
                    return message
                }
            }
            current = current.cause
        }
        return throwable?.message
    }

    private companion object {
        private const val CIRCUIT_BREAKER_THRESHOLD = 3
    }
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

private suspend fun <T> runDomainCatchingSuspend(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (throwable: Throwable) {
        when (throwable) {
            is TimeoutCancellationException -> Result.failure(throwable.toDomainException())
            is CancellationException -> throw throwable
            is DomainException -> Result.failure(throwable)
            else -> Result.failure(throwable.toDomainException())
        }
    }
}

private inline fun <T> runDomainCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (throwable: Throwable) {
        when (throwable) {
            is TimeoutCancellationException -> Result.failure(throwable.toDomainException())
            is CancellationException -> throw throwable
            is DomainException -> Result.failure(throwable)
            else -> Result.failure(throwable.toDomainException())
        }
    }
}

private fun <T> Result<T>.mapDomainFailure(): Result<T> {
    val error = exceptionOrNull() ?: return this
    if (error is DomainException) return this
    if (error is CancellationException && error !is TimeoutCancellationException) {
        return this
    }
    return Result.failure(error.toDomainException())
}

private tailrec fun resolveDomainErrorCode(throwable: Throwable?): DomainErrorCode {
    throwable ?: return DomainErrorCode.PDF_MALFORMED
    return when (throwable) {
        is DomainException -> throwable.code
        is TimeoutCancellationException -> DomainErrorCode.IO_TIMEOUT
        is InterruptedIOException -> DomainErrorCode.IO_TIMEOUT
        is RemotePdfException -> when (throwable.reason) {
            RemotePdfException.Reason.NETWORK -> DomainErrorCode.IO_TIMEOUT
            RemotePdfException.Reason.NETWORK_RETRY_EXHAUSTED -> DomainErrorCode.IO_TIMEOUT
            RemotePdfException.Reason.CORRUPTED -> DomainErrorCode.PDF_MALFORMED
            RemotePdfException.Reason.CIRCUIT_OPEN -> DomainErrorCode.IO_TIMEOUT
            RemotePdfException.Reason.UNSAFE -> DomainErrorCode.PDF_MALFORMED
            RemotePdfException.Reason.FILE_TOO_LARGE -> DomainErrorCode.IO_TIMEOUT
        }
        is PdfOpenException -> when (throwable.reason) {
            PdfOpenException.Reason.CORRUPTED -> DomainErrorCode.PDF_MALFORMED
            PdfOpenException.Reason.ACCESS_DENIED -> DomainErrorCode.IO_TIMEOUT
            PdfOpenException.Reason.UNSUPPORTED -> DomainErrorCode.PDF_MALFORMED
        }
        is PdfRenderException -> when (throwable.reason) {
            PdfRenderException.Reason.PAGE_TOO_LARGE -> DomainErrorCode.RENDER_OOM
            PdfRenderException.Reason.MALFORMED_PAGE -> DomainErrorCode.PDF_MALFORMED
        }
        is OutOfMemoryError -> DomainErrorCode.RENDER_OOM
        else -> resolveDomainErrorCode(throwable.cause)
    }
}

private fun Throwable.toDomainException(): DomainException {
    return if (this is DomainException) {
        this
    } else {
        DomainException(resolveDomainErrorCode(this), this)
    }
}

interface AdaptiveFlowUseCase {
    val readingSpeed: StateFlow<Float>
    val swipeSensitivity: StateFlow<Float>
    val preloadTargets: StateFlow<List<Int>>
    val uiUnderLoad: StateFlow<Boolean>
    val frameIntervalMillis: StateFlow<Float>

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
    override val frameIntervalMillis: StateFlow<Float>
        get() = manager.frameIntervalMillis

    override fun start() = manager.start()
    override fun stop() = manager.stop()
    override fun trackPageChange(pageIndex: Int, totalPages: Int) =
        manager.trackPageChange(pageIndex, totalPages)

    override fun isUiUnderLoad(): Boolean = manager.isUiUnderLoad()
}
