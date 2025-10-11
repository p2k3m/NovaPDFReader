package com.novapdf.reader.data

import android.app.Application
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.asTestMainDispatcher
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import com.novapdf.reader.model.RectSnapshot
import kotlin.text.Charsets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    manifest = Config.NONE,
    sdk = [
        Build.VERSION_CODES.LOLLIPOP,
        Build.VERSION_CODES.M,
        Build.VERSION_CODES.Q,
        Build.VERSION_CODES.TIRAMISU,
    ]
)
class AnnotationRepositoryExportImportCompatibilityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun resetStorage() {
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
    }

    @Test
    fun exportAndImportAcrossDocumentsPersistsAnnotations() = runTest {
        val repository = AnnotationRepository(context, dispatchers = testDispatchers())
        val documentA = "content://com.novapdf.reader/docs/primary.pdf"
        val documentB = "s3://fixtures/documents/secondary.pdf"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 2,
            points = listOf(
                PointSnapshot(0f, 0f),
                PointSnapshot(10f, 12f),
                PointSnapshot(24f, 32f)
            ),
            color = 0xFFFF5722L,
            strokeWidth = 6f
        )
        val note = AnnotationCommand.Text(
            pageIndex = 4,
            text = "Reminder",
            position = PointSnapshot(42f, 18f),
            color = 0xFF4CAF50L
        )

        repository.addAnnotation(documentA, stroke)
        repository.addAnnotation(documentB, note)
        advanceUntilIdle()

        assertEquals(setOf(documentA, documentB), repository.trackedDocumentIds())

        val exportA = repository.saveAnnotations(documentA)
        val exportB = repository.saveAnnotations(documentB)

        assertNotNull("Export should return the annotation preference file", exportA)
        assertNotNull("Export should return the annotation preference file", exportB)

        val exportedFile = AnnotationRepository.preferenceFile(context)
        assertTrue("Annotation preference file should exist after export", exportedFile.exists())

        val encodedA = documentA.encodeDocumentId()
        val encodedB = documentB.encodeDocumentId()
        val fileContents = exportedFile.readText()
        assertTrue("Exported file should contain entry for document A", fileContents.contains("name=\"$encodedA\""))
        assertTrue("Exported file should contain entry for document B", fileContents.contains("name=\"$encodedB\""))

        val recovered = AnnotationRepository(context, dispatchers = testDispatchers())
        assertEquals(listOf(stroke), recovered.annotationsForDocument(documentA))
        assertEquals(listOf(note), recovered.annotationsForDocument(documentB))
    }

    @Test
    fun replaceAnnotationsImportsPayloadWithoutAffectingOtherDocuments() = runTest {
        val repository = AnnotationRepository(context, dispatchers = testDispatchers())
        val documentA = "content://com.novapdf.reader/docs/source.pdf"
        val documentB = "file:///storage/emulated/0/Download/notes.pdf"
        val initial = AnnotationCommand.Highlight(
            pageIndex = 1,
            rect = RectSnapshot(left = 4f, top = 6f, right = 32f, bottom = 40f),
            color = 0xFF3F51B5L
        )
        val other = AnnotationCommand.Text(
            pageIndex = 7,
            text = "Existing",
            position = PointSnapshot(12f, 44f),
            color = 0xFF009688L
        )
        repository.addAnnotation(documentA, initial)
        repository.addAnnotation(documentB, other)
        advanceUntilIdle()
        repository.saveAnnotations(documentA)
        repository.saveAnnotations(documentB)

        val imported = listOf(
            AnnotationCommand.Stroke(
                pageIndex = 0,
                points = listOf(PointSnapshot(1f, 1f), PointSnapshot(8f, 16f)),
                color = 0xFFFFC107L,
                strokeWidth = 4f
            ),
            AnnotationCommand.Text(
                pageIndex = 3,
                text = "Imported",
                position = PointSnapshot(24f, 12f),
                color = 0xFF9C27B0L
            )
        )

        repository.replaceAnnotations(documentA, imported)
        advanceUntilIdle()
        repository.saveAnnotations(documentA)

        val recovered = AnnotationRepository(context, dispatchers = testDispatchers())
        assertEquals(imported, recovered.annotationsForDocument(documentA))
        assertEquals(listOf(other), recovered.annotationsForDocument(documentB))
        assertEquals(setOf(documentA, documentB), recovered.trackedDocumentIds())
    }
}

private fun String.encodeDocumentId(): String {
    return Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.testDispatchers(): TestCoroutineDispatchers {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return TestCoroutineDispatchers(
        io = dispatcher,
        default = dispatcher,
        main = dispatcher.asTestMainDispatcher()
    )
}
