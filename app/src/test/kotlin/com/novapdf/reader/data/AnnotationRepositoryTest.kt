package com.novapdf.reader.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AnnotationRepositoryTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun addAndPersistAnnotations() = runTest {
        val repository = AnnotationRepository(context)
        val documentId = "test-doc"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 1,
            points = listOf(PointSnapshot(0f, 0f), PointSnapshot(10f, 10f)),
            color = 0xFFFF0000,
            strokeWidth = 6f
        )

        repository.addAnnotation(documentId, stroke)
        val annotations = repository.annotationsForDocument(documentId)
        assertEquals(1, annotations.size)
        assertEquals(setOf(documentId), repository.trackedDocumentIds())

        val file = repository.saveAnnotations(documentId)
        assertNotNull(file)
        val prefsFile = file!!
        assertEquals(true, prefsFile.exists())
        val fileContents = prefsFile.readText()
        assertFalse(fileContents.contains(documentId))
        assertFalse(fileContents.contains("{"))
    }
}
