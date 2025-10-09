package com.novapdf.reader.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.novapdf.reader.TestPdfApp
import com.novapdf.reader.data.AnnotationRepository
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import com.novapdf.reader.engine.AdaptiveFlowManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = TestPdfApp::class)
class DocumentMaintenanceWorkerTest {

    private lateinit var app: Context
    private lateinit var annotationRepository: AnnotationRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        annotationRepository = AnnotationRepository(app)
        AnnotationRepository.preferenceFile(app).let { prefsFile ->
            prefsFile.delete()
            prefsFile.parentFile?.let { parent ->
                File(parent, "${prefsFile.name}.bak").delete()
            }
        }
        File(app.filesDir, "exports").deleteRecursively()
    }

    @After
    fun tearDown() {
        annotationRepository.trackedDocumentIds().forEach { annotationRepository.clearInMemory(it) }
    }

    @Test
    fun `worker persists annotations and bookmarks`() = runTest {
        val documentId = "worker-doc"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 0,
            points = listOf(PointSnapshot(0f, 0f), PointSnapshot(5f, 5f)),
            color = 0xFF00FF00L,
            strokeWidth = 4f
        )
        annotationRepository.addAnnotation(documentId, stroke)

        val bookmarkManager = org.mockito.kotlin.mock<com.novapdf.reader.data.BookmarkManager>()
        org.mockito.kotlin.whenever(bookmarkManager.bookmarkedDocumentIds()).thenReturn(listOf(documentId))
        org.mockito.kotlin.whenever(bookmarkManager.bookmarks(documentId)).thenReturn(listOf(3))

        val adaptiveFlowManager = org.mockito.kotlin.mock<AdaptiveFlowManager>()
        org.mockito.kotlin.whenever(adaptiveFlowManager.isUiUnderLoad()).thenReturn(false)

        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                return DocumentMaintenanceWorker(
                    appContext,
                    workerParameters,
                    annotationRepository,
                    bookmarkManager,
                    adaptiveFlowManager,
                )
            }
        }

        val worker = TestListenableWorkerBuilder<DocumentMaintenanceWorker>(app)
            .setWorkerFactory(workerFactory)
            .build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)

        val encodedId = DocumentMaintenanceWorker.encodeDocumentId(documentId)
        val annotationFile = AnnotationRepository.preferenceFile(app)
        val exportFile = File(app.filesDir, "exports/${encodedId}_bookmarks.json")

        assertTrue(annotationFile.exists())
        assertTrue(exportFile.exists())
        val annotationContents = annotationFile.readText()
        assertFalse(annotationContents.contains(documentId))
        assertFalse(annotationContents.contains("{"))
    }
}
