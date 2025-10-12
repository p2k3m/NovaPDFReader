package com.novapdf.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novapdf.reader.CacheFileNames
import com.novapdf.reader.cache.DefaultCacheDirectories
import com.novapdf.reader.data.PdfDocumentRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class LargePdfInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var stressDocumentFactory: StressDocumentFactory

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun openLargeAndUnusualDocumentWithoutAnrOrCrash() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = PdfDocumentRepository(
            context,
            cacheDirectories = DefaultCacheDirectories(context),
        )
        try {
            val stressUri = stressDocumentFactory.installStressDocument(context)
            val session = withTimeout(60_000) { repository.open(stressUri) }
            assertNotNull("Stress PDF should open successfully", session)
            val pdfSession = requireNotNull(session)
            assertEquals(32, pdfSession.pageCount)

            val sampleIndices = linkedSetOf(
                0,
                1,
                2,
                3,
                pdfSession.pageCount / 2,
                pdfSession.pageCount - 1,
            )
            for (index in sampleIndices) {
                val pageSize = withTimeout(30_000) { repository.getPageSize(index) }
                assertNotNull("Page $index should report a size", pageSize)
                val size = requireNotNull(pageSize)
                assertTrue("Page width should be > 0", size.width > 0)
                assertTrue("Page height should be > 0", size.height > 0)

                when (index % 4) {
                    0 -> assertTrue("Variant 0 should be portrait", size.height > size.width)
                    1 -> assertTrue("Variant 1 should be landscape", size.width > size.height)
                    2 -> assertTrue(
                        "Variant 2 should resemble a tall infographic",
                        size.height.toDouble() / size.width.toDouble() >= 2.5,
                    )
                    3 -> assertTrue(
                        "Variant 3 should resemble a wide panorama",
                        size.width.toDouble() / size.height.toDouble() >= 2.5,
                    )
                }

                val renderWidth = size.width
                    .coerceAtLeast(size.height)
                    .coerceAtMost(2000)
                val bitmap = withTimeout(60_000) { repository.renderPage(index, renderWidth) }
                assertNotNull("Page $index should render", bitmap)
                val renderedPage = requireNotNull(bitmap)
                renderedPage.recycle()
            }

            val cacheDir = File(context.cacheDir, CacheFileNames.INSTRUMENTATION_SCREENSHOT_DIRECTORY)
                .apply { mkdirs() }
            assertTrue("Cache directory should exist", cacheDir.exists())
        } finally {
            repository.dispose()
        }
    }
}
