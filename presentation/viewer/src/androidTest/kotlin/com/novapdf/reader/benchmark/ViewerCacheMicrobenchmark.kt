package com.novapdf.reader.benchmark

import android.graphics.Bitmap
import android.os.Build
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.novapdf.reader.PageCacheKey
import com.novapdf.reader.PdfViewerViewModel
import com.novapdf.reader.model.PageRenderProfile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ViewerCacheMicrobenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun bitmapCacheHitMissCycles() {
        val cacheFactory = PdfViewerViewModel.defaultPageBitmapCacheFactory()
        val cache = cacheFactory.create(maxBytes = 8 * 1024 * 1024) { bitmap -> bitmap.sizeInBytes }
        val key = PageCacheKey("stress-doc", 0, 1080, PageRenderProfile.HIGH_DETAIL)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        var iterations = 0
        try {
            benchmarkRule.measureRepeated {
                runWithTimingDisabled {
                    cache.evictAll()
                    iterations++
                }
                assertNull("First lookup should miss to exercise cache miss path", cache.get(key))
                cache.put(key, bitmap)
                assertNotNull("Second lookup should hit the cache", cache.get(key))
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("Expected cache miss accounting to reflect the exercised iterations", cache.missCount() >= iterations)
        assertTrue("Expected cache hit accounting to reflect the exercised iterations", cache.hitCount() >= iterations)
    }

    @Test
    fun cacheTrimEvictionStress() {
        val cacheFactory = PdfViewerViewModel.defaultPageBitmapCacheFactory()
        val cache = cacheFactory.create(maxBytes = 4 * 1024 * 1024) { bitmap -> bitmap.sizeInBytes }
        val bitmaps = List(4) { Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888) }
        try {
            benchmarkRule.measureRepeated {
                runWithTimingDisabled { cache.evictAll() }
                bitmaps.forEachIndexed { index, bitmap ->
                    val key = PageCacheKey("stress-doc", index, 1080, PageRenderProfile.HIGH_DETAIL)
                    cache.put(key, bitmap)
                }
                cache.trimToFraction(0.25f)
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }
        assertTrue("Cache trim should trigger evictions over time", cache.evictionCount() > 0)
    }
}

private val Bitmap.sizeInBytes: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        allocationByteCount
    } else {
        @Suppress("DEPRECATION")
        byteCount
    }
