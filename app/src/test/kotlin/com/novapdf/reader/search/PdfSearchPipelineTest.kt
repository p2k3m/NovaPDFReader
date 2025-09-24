package com.novapdf.reader.search

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.novapdf.reader.model.RectSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PdfSearchPipelineTest {

    @Test
    fun `collectMatchesFromRuns links normalized tokens to bounding boxes`() {
        val runs = listOf(
            TextRunSnapshot(
                text = "Search",
                bounds = listOf(RectF(0f, 0f, 200f, 40f))
            ),
            TextRunSnapshot(
                text = "Engine",
                bounds = listOf(RectF(0f, 50f, 220f, 90f))
            )
        )
        val normalizedQuery = normalizeSearchQuery("search engine")

        val matches = collectMatchesFromRuns(runs, normalizedQuery, pageWidth = 220, pageHeight = 400)

        assertEquals(1, matches.size)
        val match = matches.first()
        assertEquals(0, match.indexInPage)
        assertEquals(2, match.boundingBoxes.size)
        val first = match.boundingBoxes[0]
        val second = match.boundingBoxes[1]
        assertRectApprox(RectSnapshot(0f, 0f, 200f / 220f, 40f / 400f), first)
        assertRectApprox(RectSnapshot(0f, 50f / 400f, 220f / 220f, 90f / 400f), second)
    }

    @Test
    fun `detectTextRegions extracts edge-dense areas from bitmap`() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        canvas.drawRect(RectF(20f, 40f, 180f, 70f), paint)
        canvas.drawRect(RectF(30f, 120f, 160f, 150f), paint)

        val regions = detectTextRegions(bitmap)

        assertTrue(regions.size >= 2)
        val firstLine = RectSnapshot(20f / 200f, 40f / 200f, 180f / 200f, 70f / 200f)
        val secondLine = RectSnapshot(30f / 200f, 120f / 200f, 160f / 200f, 150f / 200f)
        assertTrue(regions.any { overlaps(it, firstLine) })
        assertTrue(regions.any { overlaps(it, secondLine) })
        bitmap.recycle()
    }

    private fun assertRectApprox(expected: RectSnapshot, actual: RectSnapshot) {
        assertTrue(abs(expected.left - actual.left) < 0.05f)
        assertTrue(abs(expected.top - actual.top) < 0.05f)
        assertTrue(abs(expected.right - actual.right) < 0.05f)
        assertTrue(abs(expected.bottom - actual.bottom) < 0.05f)
    }

    private fun overlaps(a: RectSnapshot, b: RectSnapshot): Boolean {
        val horizontal = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val vertical = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return horizontal > 0f && vertical > 0f
    }

}
