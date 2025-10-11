package com.novapdf.reader.work

import android.content.Context
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.field
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class DocumentMaintenanceScheduler(context: Context) {

    private val appContext = context.applicationContext

    fun scheduleAutosave(documentId: String? = null) {
        withWorkManager("scheduleAutosave") { workManager ->
            val builder = OneTimeWorkRequestBuilder<DocumentMaintenanceWorker>()
                .setInitialDelay(AUTOSAVE_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
                .addTag(DocumentMaintenanceWorker.TAG_AUTOSAVE)
            documentId?.let {
                builder.setInputData(workDataOf(DocumentMaintenanceWorker.KEY_DOCUMENT_IDS to arrayOf(it)))
            }
            val request = builder.build()
            workManager.enqueueUniqueWork(
                DocumentMaintenanceWorker.AUTOSAVE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun requestImmediateSync(documentId: String? = null) {
        withWorkManager("requestImmediateSync") { workManager ->
            val builder = OneTimeWorkRequestBuilder<DocumentMaintenanceWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(DocumentMaintenanceWorker.TAG_IMMEDIATE)
            documentId?.let {
                builder.setInputData(workDataOf(DocumentMaintenanceWorker.KEY_DOCUMENT_IDS to arrayOf(it)))
            }
            val request = builder.build()
            workManager.enqueueUniqueWork(
                DocumentMaintenanceWorker.IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun ensurePeriodicSync() {
        withWorkManager("ensurePeriodicSync") { workManager ->
            val request = PeriodicWorkRequestBuilder<DocumentMaintenanceWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
                PERIODIC_FLEX_MINUTES,
                TimeUnit.MINUTES
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY_MINUTES, TimeUnit.MINUTES)
                .addTag(DocumentMaintenanceWorker.TAG_PERIODIC)
                .build()
            workManager.enqueueUniquePeriodicWork(
                DocumentMaintenanceWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    private inline fun withWorkManager(
        operation: String,
        block: (WorkManager) -> Unit,
    ) {
        val manager = runCatching { WorkManager.getInstance(appContext) }
            .getOrElse { error ->
                if (error is IllegalStateException) {
                    NovaLog.w(
                        tag = TAG,
                        message = "Skipping $operation because WorkManager is not initialised yet",
                        throwable = error,
                        field("operation", operation),
                    )
                } else {
                    NovaLog.w(
                        tag = TAG,
                        message = "Unable to obtain WorkManager for $operation",
                        throwable = error,
                        field("operation", operation),
                    )
                }
                return
            }

        try {
            block(manager)
        } catch (error: Exception) {
            NovaLog.w(
                tag = TAG,
                message = "WorkManager operation '$operation' failed",
                throwable = error,
                field("operation", operation),
            )
        }
    }

    companion object {
        private const val TAG = "DocumentMaintenanceScheduler"
        private const val AUTOSAVE_DELAY_MINUTES = 2L
        private const val BACKOFF_DELAY_MINUTES = 5L
        private const val PERIODIC_INTERVAL_MINUTES = 30L
        private const val PERIODIC_FLEX_MINUTES = 5L
    }
}
