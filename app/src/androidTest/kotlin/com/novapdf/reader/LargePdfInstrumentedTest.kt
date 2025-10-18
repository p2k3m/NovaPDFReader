package com.novapdf.reader

import android.content.Context
import android.util.Log
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

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resourceMonitorRule = DeviceResourceMonitorRule(
        contextProvider = { runCatching { ApplicationProvider.getApplicationContext<Context>() }.getOrNull() },
        logger = { message -> Log.i(TAG, message) },
        onResourceExhausted = { reason -> Log.w(TAG, "Resource exhaustion detected: $reason") },
    )

    private lateinit var deviceTimeouts: DeviceAdaptiveTimeouts
    private var openTimeoutMs: Long = DEFAULT_OPEN_TIMEOUT_MS
    private var pageSizeTimeoutMs: Long = DEFAULT_PAGE_SIZE_TIMEOUT_MS
    private var renderTimeoutMs: Long = DEFAULT_RENDER_TIMEOUT_MS

    @Inject
    lateinit var stressDocumentFactory: StressDocumentFactory

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = ApplicationProvider.getApplicationContext<Context>()
        deviceTimeouts = DeviceAdaptiveTimeouts.forContext(context)
        openTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_OPEN_TIMEOUT_MS,
            min = DEFAULT_OPEN_TIMEOUT_MS,
            max = MAX_OPEN_TIMEOUT_MS,
            extraMultiplier = 1.25,
            allowTightening = false,
        )
        pageSizeTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_PAGE_SIZE_TIMEOUT_MS,
            min = DEFAULT_PAGE_SIZE_TIMEOUT_MS,
            max = MAX_PAGE_SIZE_TIMEOUT_MS,
            allowTightening = false,
        )
        renderTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_RENDER_TIMEOUT_MS,
            min = DEFAULT_RENDER_TIMEOUT_MS,
            max = MAX_RENDER_TIMEOUT_MS,
            extraMultiplier = 1.2,
            allowTightening = false,
        )
        logTestInfo(
            "Using timeouts open=${openTimeoutMs}ms pageSize=${pageSizeTimeoutMs}ms render=${renderTimeoutMs}ms",
        )
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
            val session = withTimeout(openTimeoutMs) { repository.open(stressUri) }
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
                val pageSize = withTimeout(pageSizeTimeoutMs) { repository.getPageSize(index) }
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
                val bitmap = withTimeout(renderTimeoutMs) { repository.renderPage(index, renderWidth) }
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

    private companion object {
        private const val TAG = "LargePdfTest"
        private const val DEFAULT_OPEN_TIMEOUT_MS = 60_000L
        private const val MAX_OPEN_TIMEOUT_MS = 240_000L
        private const val DEFAULT_PAGE_SIZE_TIMEOUT_MS = 30_000L
        private const val MAX_PAGE_SIZE_TIMEOUT_MS = 120_000L
        private const val DEFAULT_RENDER_TIMEOUT_MS = 60_000L
        private const val MAX_RENDER_TIMEOUT_MS = 240_000L
    }

    private fun logTestInfo(message: String) {
        Log.i(TAG, message)
    }
}
