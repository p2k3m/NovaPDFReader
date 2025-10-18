package com.novapdf.reader

import android.content.Context
import android.graphics.ImageFormat
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shockwave.pdfium.PdfiumCore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class NativeDependencyCompatibilityTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resourceMonitorRule = DeviceResourceMonitorRule(
        contextProvider = { runCatching { ApplicationProvider.getApplicationContext<Context>() }.getOrNull() },
        logger = { message -> Log.i(TAG, message) },
        onResourceExhausted = { reason -> Log.w(TAG, "Resource exhaustion detected: $reason") },
    )

    @Inject
    lateinit var sampleDocument: SampleDocument

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun pdfiumRendersSamplePage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdfiumCore = PdfiumCore(context)
        val sampleUri = sampleDocument.installIntoCache(context)

        context.contentResolver.openFileDescriptor(sampleUri, "r").use { descriptor ->
            requireNotNull(descriptor) { "Sample PDF should resolve to a file descriptor" }
            val document = pdfiumCore.newDocument(descriptor)
            try {
                assertTrue("Sample PDF should contain at least one page", pdfiumCore.getPageCount(document) > 0)
            } finally {
                pdfiumCore.closeDocument(document)
            }
        }
    }

    @Test
    fun pdfBoxAndroidExtractsText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val sampleUri = sampleDocument.installIntoCache(context)

        context.contentResolver.openInputStream(sampleUri).use { stream ->
            requireNotNull(stream) { "Sample PDF should resolve to an input stream" }
            PDDocument.load(stream).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue("PDFBox should read the embedded sample text", text.contains("ReportLab"))
            }
        }
    }

    @Test
    fun luceneTokenizesTextOnDevice() {
        val analyzer = StandardAnalyzer()
        analyzer.tokenStream("content", "Indexed text for compatibility validation").use { tokenStream ->
            tokenStream.reset()
            val attribute = tokenStream.getAttribute(CharTermAttribute::class.java)
            val observedTokens = mutableSetOf<String>()
            while (tokenStream.incrementToken()) {
                observedTokens += attribute.toString()
            }
            tokenStream.end()
            assertTrue(
                "Lucene should provide at least one token when running on Android",
                observedTokens.isNotEmpty()
            )
        }
        analyzer.close()
    }

    @Test
    fun mlKitTextRecognizerProcessesFrame() {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val width = 8
            val height = 8
            val bytesPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
            val frameData = ByteArray(width * height * bytesPerPixel)
            val image = InputImage.fromByteArray(
                frameData,
                width,
                height,
                /* rotationDegrees = */ 0,
                InputImage.IMAGE_FORMAT_NV21
            )

            val completion = CountDownLatch(1)
            var didSucceed = false
            var failure: Exception? = null

            recognizer.process(image)
                .addOnSuccessListener {
                    didSucceed = true
                    completion.countDown()
                }
                .addOnFailureListener { error ->
                    failure = error
                    completion.countDown()
                }

            val finished = completion.await(30, TimeUnit.SECONDS)
            assertTrue("Text recognition should complete", finished)
            if (failure != null) {
                throw failure as Exception
            }
            assertTrue("Text recognition should succeed on device", didSucceed)
        } finally {
            recognizer.close()
        }
    }

    private companion object {
        private const val TAG = "NativeCompatTest"
    }
}
