package com.novapdf.reader.work

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.hilt.work.HiltWorker
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.engine.AdaptiveFlowManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@HiltWorker
class DocumentMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val annotationRepository: AnnotationRepository,
    private val bookmarkManager: BookmarkManager,
    private val adaptiveFlowManager: AdaptiveFlowManager,
) : CoroutineWorker(appContext, workerParams) {

    private val json = Json { prettyPrint = true }

    override suspend fun doWork(): Result {
        if (adaptiveFlowManager.isUiUnderLoad()) {
            return Result.retry()
        }

        val explicitTargets = inputData.getStringArray(KEY_DOCUMENT_IDS)
            ?.mapNotNull { it.takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()

        val fallbackIds = annotationRepository.trackedDocumentIds() +
            runCatching { bookmarkManager.bookmarkedDocumentIds() }.getOrDefault(emptyList())

        val documentIds = if (explicitTargets.isNotEmpty()) {
            explicitTargets
        } else {
            fallbackIds
        }.toSet()

        if (documentIds.isEmpty()) {
            return Result.success()
        }

        var needsRetry = false

        documentIds.forEach { documentId ->
            val saveResult = runCatching {
                annotationRepository.saveAnnotations(documentId)
            }
            if (saveResult.isFailure) {
                needsRetry = true
            }

            val bookmarksResult = runCatching { bookmarkManager.bookmarks(documentId) }

            val bookmarks = bookmarksResult.getOrElse {
                needsRetry = true
                emptyList()
            }

            if (bookmarks.isNotEmpty()) {
                val exportOutcome = runCatching {
                    exportBookmarks(documentId, bookmarks)
                }
                if (exportOutcome.isFailure) {
                    needsRetry = true
                }
            }
        }

        return if (needsRetry) Result.retry() else Result.success()
    }

    private suspend fun exportBookmarks(documentId: String, bookmarks: List<Int>) {
        withContext(Dispatchers.IO) {
            val encodedId = encodeDocumentId(documentId)
            val exportDir = File(applicationContext.filesDir, EXPORT_DIR_NAME).apply { mkdirs() }
            val exportFile = File(exportDir, "$encodedId$BOOKMARK_SUFFIX")
            exportFile.writeText(json.encodeToString(bookmarks))
        }
    }

    companion object {
        const val KEY_DOCUMENT_IDS = "document_ids"
        const val AUTOSAVE_WORK_NAME = "document_annotation_autosave"
        const val PERIODIC_WORK_NAME = "document_annotation_periodic"
        const val IMMEDIATE_WORK_NAME = "document_annotation_immediate"
        const val TAG_AUTOSAVE = "document_autosave"
        const val TAG_IMMEDIATE = "document_autosave_immediate"
        const val TAG_PERIODIC = "document_autosave_periodic"
        private const val EXPORT_DIR_NAME = "exports"
        private const val BOOKMARK_SUFFIX = "_bookmarks.json"

        fun encodeDocumentId(documentId: String): String =
            Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
