package com.novapdf.reader.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.novapdf.reader.NovaPdfApp
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = NovaPdfApp::class)
class DocumentMaintenanceWorkerTest {

    private lateinit var app: NovaPdfApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.filesDir, "annotations").deleteRecursively()
        File(app.filesDir, "exports").deleteRecursively()
    }

    @Test
    fun `worker persists annotations and bookmarks`() = runTest {
        val documentId = "worker-doc"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 0,
            points = listOf(PointSnapshot(0f, 0f), PointSnapshot(5f, 5f)),
            color = 0xFF00FF00.toInt(),
            strokeWidth = 4f
        )
        app.annotationRepository.addAnnotation(documentId, stroke)
        app.bookmarkManager.toggleBookmark(documentId, 3)

        val worker = TestListenableWorkerBuilder<DocumentMaintenanceWorker>(app)
            .build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)

        val encodedId = DocumentMaintenanceWorker.encodeDocumentId(documentId)
        val annotationFile = File(app.filesDir, "annotations/$encodedId.json")
        val exportFile = File(app.filesDir, "exports/${encodedId}_bookmarks.json")

        assertTrue(annotationFile.exists())
        assertTrue(exportFile.exists())
    }
}
