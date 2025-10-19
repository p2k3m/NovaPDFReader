package com.novapdf.reader

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FractionalBitmapLruCacheTest {

    @Test
    fun trimToFractionEvictsExpectedProportion() {
        val cache = FractionalBitmapLruCache<String>(
            maxSizeBytes = 4 * 4096,
            sizeCalculator = { bitmap -> bitmap.byteCount }
        )
        val entries = (0 until 4).map { index ->
            val key = "page-$index"
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            cache.put(key, bitmap)
            key to bitmap
        }

        val initialSize = cache.size()
        cache.trimToFraction(0.5f)

        val remaining = cache.snapshot()
        assertTrue("Cache size should shrink after trim", cache.size() < initialSize)
        assertEquals(2, remaining.size)
        assertNull(cache.get("page-0"))
        assertNull(cache.get("page-1"))
        assertNotNull(cache.get("page-2"))
        assertNotNull(cache.get("page-3"))

        cache.evictAll()
        entries.forEach { (_, bitmap) ->
            bitmap.recycle()
        }
    }

    @Test
    fun evictsLeastRecentlyUsedDuringRapidInsertions() {
        val cache = FractionalBitmapLruCache<String>(
            maxSizeBytes = 2 * 4096,
            sizeCalculator = { bitmap -> bitmap.byteCount }
        )
        val first = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val third = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

        cache.put("first", first)
        cache.put("second", second)
        assertNotNull(cache.get("first"))

        cache.put("third", third)

        assertNotNull(cache.get("first"))
        assertNull(cache.get("second"))
        assertNotNull(cache.get("third"))

        cache.evictAll()

        listOf(first, second, third).forEach(Bitmap::recycle)
    }
}
