package com.novapdf.reader.search

import android.app.Application
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.TestPdfApp
import com.novapdf.reader.asTestMainDispatcher
import com.novapdf.reader.cache.DefaultCacheDirectories
import com.novapdf.reader.coroutines.TestCoroutineDispatchers
import com.novapdf.reader.data.PdfDocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class LuceneSearchCoordinatorMetadataTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val mainDispatcher = dispatcher.asTestMainDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `readDocumentMetadata parses persisted cache json`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val coordinator = createCoordinator(context)
        val documentId = "sample-document"
        val encoded = Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val indexRoot = File(File(context.cacheDir, "pdf-cache"), "indexes")
        if (!indexRoot.exists()) {
            indexRoot.mkdirs()
        }
        val directory = File(indexRoot, encoded)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val metadataFile = File(directory, "metadata.json")
        metadataFile.writeText("""{"version":2,"pageCount":7,"documentMtimeMs":12345}""")

        val method = coordinator.javaClass.getDeclaredMethod("readDocumentMetadata", String::class.java).apply {
            isAccessible = true
        }
        val metadata = method.invoke(coordinator, documentId)

        assertEquals(
            "DocumentCacheMetadata(version=2, pageCount=7, documentMtimeMs=12345)",
            metadata?.toString()
        )
    }

    @Test
    fun `readDocumentMetadata returns null when values are invalid`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val coordinator = createCoordinator(context)
        val documentId = "corrupt"
        val encoded = Base64.encodeToString(documentId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val indexRoot = File(File(context.cacheDir, "pdf-cache"), "indexes")
        if (!indexRoot.exists()) {
            indexRoot.mkdirs()
        }
        val directory = File(indexRoot, encoded)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val metadataFile = File(directory, "metadata.json")
        metadataFile.writeText("""{"version":0,"pageCount":-1,"documentMtimeMs":-4}""")

        val method = coordinator.javaClass.getDeclaredMethod("readDocumentMetadata", String::class.java).apply {
            isAccessible = true
        }
        val metadata = method.invoke(coordinator, documentId)

        assertNull(metadata)
    }

    private fun createCoordinator(context: Application): LuceneSearchCoordinator {
        val repository = mock<PdfDocumentRepository>()
        val dispatchers = TestCoroutineDispatchers(dispatcher, dispatcher, mainDispatcher)
        return LuceneSearchCoordinator(
            context,
            repository,
            dispatchers,
            DefaultCacheDirectories(context),
        )
    }
}
