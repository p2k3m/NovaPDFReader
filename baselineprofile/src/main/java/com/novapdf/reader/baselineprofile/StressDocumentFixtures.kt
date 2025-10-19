package com.novapdf.reader.baselineprofile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.HarnessDocument
import com.novapdf.reader.HarnessOverrideLoader
import com.novapdf.reader.util.sanitizeCacheFileName
import java.io.File
import java.io.IOException
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

internal object StressDocumentFixtures {
    private val LARGE_CACHE_FILE_NAME = sanitizeCacheFileName(
        raw = "baseline-stress-large.pdf",
        fallback = "baseline-stress-large.pdf",
        label = "baseline stress PDF cache file",
    )
    private const val PAGE_COUNT = 32

    fun ensureStressDocument(context: Context): HarnessDocument {
        val arguments = runCatching { InstrumentationRegistry.getArguments() }.getOrNull()
        val override = HarnessOverrideLoader.loadDocumentFactory(context, arguments)
            ?.runCatching { create(context) }
            ?.onFailure { error ->
                Log.w(TAG, "Harness document factory failed; falling back to local stress PDF", error)
            }
            ?.getOrNull()
        if (override != null) {
            return override
        }

        val cacheFile = File(context.cacheDir, LARGE_CACHE_FILE_NAME)
        cacheFile.parentFile?.mkdirs()
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            generateStressDocument(cacheFile)
        }
        return HarnessDocument.Local(Uri.fromFile(cacheFile))
    }

    private fun generateStressDocument(destination: File) {
        val parentDir = destination.parentFile ?: throw IOException("Missing cache directory for stress PDF")
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Unable to create cache directory for stress PDF")
        }

        val tempFile = File(parentDir, destination.name + ".tmp")
        if (tempFile.exists() && !tempFile.delete()) {
            throw IOException("Unable to clear stale stress PDF cache")
        }

        val pdfDocument = PdfDocument()
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 36f
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val backgroundPaint = Paint().apply {
            style = Paint.Style.FILL
        }

        val pageSizes = buildList {
            add(PdfDocument.PageInfo.Builder(2480, 3508, 1).create())
            add(PdfDocument.PageInfo.Builder(3508, 2480, 2).create())
            add(PdfDocument.PageInfo.Builder(2000, 6000, 3).create())
            add(PdfDocument.PageInfo.Builder(6000, 2000, 4).create())
        }

        try {
            repeat(PAGE_COUNT) { index ->
                val baseInfo = pageSizes[index % pageSizes.size]
                val pageInfo = PdfDocument.PageInfo.Builder(
                    baseInfo.pageWidth,
                    baseInfo.pageHeight,
                    index + 1
                ).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                drawStressContent(canvas, index, paint, backgroundPaint)
                pdfDocument.finishPage(page)
            }

            tempFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
                output.flush()
            }
        } finally {
            pdfDocument.close()
        }

        if (destination.exists() && !destination.delete()) {
            tempFile.delete()
            throw IOException("Unable to replace cached stress PDF")
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.delete()
            throw IOException("Unable to move stress PDF into cache")
        }
    }

    private fun drawStressContent(
        canvas: Canvas,
        pageIndex: Int,
        paint: Paint,
        backgroundPaint: Paint,
    ) {
        val pageNumber = pageIndex + 1
        val baseColor = Color.HSVToColor(
            floatArrayOf(((pageIndex * 47) % 360).toFloat(), 0.3f + (pageIndex % 5) * 0.1f, 0.9f)
        )
        backgroundPaint.color = baseColor
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), backgroundPaint)

        paint.color = Color.BLACK
        canvas.drawText(
            "Stress document page $pageNumber/${PAGE_COUNT}",
            64f,
            96f,
            paint
        )

        val gridPaint = Paint().apply {
            color = Color.argb(96, 0, 0, 0)
            strokeWidth = 2f
        }
        val gridSpacing = max(120, min(canvas.width, canvas.height) / 12)
        var x = 0
        while (x < canvas.width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), canvas.height.toFloat(), gridPaint)
            x += gridSpacing
        }
        var y = 0
        while (y < canvas.height) {
            canvas.drawLine(0f, y.toFloat(), canvas.width.toFloat(), y.toFloat(), gridPaint)
            y += gridSpacing
        }

        val accentPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = Color.rgb(
                (128 + ((pageIndex * 37) % 127)).coerceIn(0, 255),
                (64 + ((pageIndex * 53) % 191)).coerceIn(0, 255),
                (96 + ((pageIndex * 29) % 159)).coerceIn(0, 255)
            )
        }
        val margin = gridSpacing / 2f
        val rectWidth = canvas.width - margin * 2
        val rectHeight = canvas.height - margin * 2
        canvas.drawRect(margin, margin, margin + rectWidth, margin + rectHeight, accentPaint)

        val diagPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.argb(160, 255, 255, 255)
        }
        canvas.drawLine(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), diagPaint)
        canvas.drawLine(
            0f,
            canvas.height.toFloat(),
            canvas.width.toFloat(),
            0f,
            diagPaint
        )

        paint.color = Color.DKGRAY
        val textBlock = buildString {
            appendLine("Generated for baseline profiling.")
            appendLine("Canvas size: ${canvas.width}x${canvas.height}")
            appendLine("Aspect ratio: %.2f".format(canvas.width.toFloat() / canvas.height.toFloat()))
            appendLine("Checksum seed: ${(pageIndex * 31).absoluteValue}")
        }
        val lines = textBlock.trimEnd().split('\n')
        var textY = canvas.height - margin * 1.5f
        for (line in lines.asReversed()) {
            canvas.drawText(line, margin, textY, paint)
            textY -= paint.textSize * 1.4f
        }
    }

    private const val TAG = "StressDocument"
}
