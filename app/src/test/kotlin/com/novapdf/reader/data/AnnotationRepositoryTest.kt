package com.novapdf.reader.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.asTestMainDispatcher
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import com.novapdf.reader.model.RectSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
        val repository = AnnotationRepository(context, dispatchers = testDispatchers())
        val documentId = "test-doc"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 1,
            points = listOf(PointSnapshot(0f, 0f), PointSnapshot(10f, 10f)),
            color = 0xFFFF0000,
            strokeWidth = 6f
        )

        repository.addAnnotation(documentId, stroke)
        advanceUntilIdle()
        val annotations = repository.annotationsForDocument(documentId)
        assertEquals(1, annotations.size)
        assertEquals(setOf(documentId), repository.trackedDocumentIds())

        val file = repository.saveAnnotations(documentId)
        assertNotNull(file)
        val prefsFile = file!!
        assertTrue(prefsFile.exists())
        val fileContents = prefsFile.readText()
        assertFalse(fileContents.contains(documentId))
        assertFalse(fileContents.contains("{"))
    }

    @Test
    fun fallsBackToUnencryptedPreferencesWhenSecureStorageUnavailable() = runTest {
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
        var fallbackInvoked = false
        val repository = AnnotationRepository(
            context,
            securePreferencesProvider = { throw IllegalStateException("secure storage unavailable") },
            fallbackPreferencesProvider = { appContext ->
                fallbackInvoked = true
                appContext.getSharedPreferences(AnnotationRepository.PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
            },
            dispatchers = testDispatchers()
        )

        val annotation = AnnotationCommand.Highlight(
            pageIndex = 0,
            rect = RectSnapshot(0f, 0f, 10f, 10f),
            color = 0xff0000
        )
        repository.addAnnotation("doc", annotation)
        advanceUntilIdle()

        val savedFile = repository.saveAnnotations("doc")

        assertTrue("fallback preferences should be used", fallbackInvoked)
        assertNotNull("annotations should be persisted when fallback is active", savedFile)
        assertTrue(
            "fallback preferences file should exist",
            AnnotationRepository.preferenceFile(context).exists()
        )
    }

    @Test
    fun recoversPersistedAnnotationsOnInitialization() = runTest {
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
        val documentId = "persisted-doc"
        val stroke = AnnotationCommand.Stroke(
            pageIndex = 0,
            points = listOf(PointSnapshot(2f, 2f), PointSnapshot(3f, 3f)),
            color = 0xFF00FF00,
            strokeWidth = 4f
        )

        val repository = AnnotationRepository(context, dispatchers = testDispatchers())
        repository.addAnnotation(documentId, stroke)
        advanceUntilIdle()
        repository.saveAnnotations(documentId)

        val recovered = AnnotationRepository(context, dispatchers = testDispatchers())

        val annotations = recovered.annotationsForDocument(documentId)
        assertEquals(1, annotations.size)
        assertEquals(stroke, annotations.first())
    }

    @Test
    fun recoversDraftsFromWriteAheadLog() = runTest {
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
        val documentId = "draft-doc"
        val highlight = AnnotationCommand.Highlight(
            pageIndex = 2,
            rect = RectSnapshot(5f, 5f, 10f, 10f),
            color = 0xFFAA00FF
        )

        val first = AnnotationRepository(context, dispatchers = testDispatchers())
        first.addAnnotation(documentId, highlight)
        advanceUntilIdle()

        val recovered = AnnotationRepository(context, dispatchers = testDispatchers())

        val annotations = recovered.annotationsForDocument(documentId)
        assertEquals(1, annotations.size)
        assertEquals(highlight, annotations.first())
    }

    @Test
    fun savingAnnotationsClearsWriteAheadLog() = runTest {
        AnnotationRepository.preferenceFile(context).delete()
        AnnotationRepository.walDirectory(context).deleteRecursively()
        val documentId = "cleanup-doc"
        val text = AnnotationCommand.Text(
            pageIndex = 1,
            text = "Draft",
            position = PointSnapshot(1f, 1f),
            color = 0xFF0000FF
        )

        val repository = AnnotationRepository(context, dispatchers = testDispatchers())
        repository.addAnnotation(documentId, text)
        advanceUntilIdle()

        val walFile = AnnotationRepository.walFile(context, documentId)
        assertTrue("write-ahead log should be created", walFile.exists())

        repository.saveAnnotations(documentId)

        assertFalse("write-ahead log should be cleared after saving", walFile.exists())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.testDispatchers(): TestCoroutineDispatchers {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return TestCoroutineDispatchers(
        io = dispatcher,
        default = dispatcher,
        main = dispatcher.asTestMainDispatcher()
    )
}
