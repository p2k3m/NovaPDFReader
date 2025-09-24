package com.novapdf.reader.work

import android.content.Context
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
    private val workManager = WorkManager.getInstance(appContext)

    fun scheduleAutosave(documentId: String? = null) {
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

    fun requestImmediateSync(documentId: String? = null) {
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

    fun ensurePeriodicSync() {
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

    companion object {
        private const val AUTOSAVE_DELAY_MINUTES = 2L
        private const val BACKOFF_DELAY_MINUTES = 5L
        private const val PERIODIC_INTERVAL_MINUTES = 30L
        private const val PERIODIC_FLEX_MINUTES = 5L
    }
}
