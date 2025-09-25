package com.novapdf.reader.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfViewerScreenEdgeCaseTest {

    @Test
    fun determineTileGridMaintainsMinimumSpanInMultiWindowMode() {
        val method = Class.forName("com.novapdf.reader.PdfViewerScreenKt")
            .getDeclaredMethod(
                "determineTileGrid",
                Float::class.javaPrimitiveType,
                Density::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }

        val density = Density(1f)
        val result = method.invoke(null, 1f, density, 320f, 512f)
        val columnsField = result.javaClass.getDeclaredField("columns").apply { isAccessible = true }
        val rowsField = result.javaClass.getDeclaredField("rows").apply { isAccessible = true }

        val columns = columnsField.getInt(result)
        val rows = rowsField.getInt(result)

        assertEquals(2, columns)
        assertEquals(2, rows)
    }

    @Test
    fun determineTileGridCapsTileCountForLargeSurfaces() {
        val method = Class.forName("com.novapdf.reader.PdfViewerScreenKt")
            .getDeclaredMethod(
                "determineTileGrid",
                Float::class.javaPrimitiveType,
                Density::class.java,
                Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }

        val density = Density(3f)
        val result = method.invoke(null, 4f, density, 2400f, 3200f)
        val columnsField = result.javaClass.getDeclaredField("columns").apply { isAccessible = true }
        val rowsField = result.javaClass.getDeclaredField("rows").apply { isAccessible = true }

        val columns = columnsField.getInt(result)
        val rows = rowsField.getInt(result)

        assertEquals(4, columns)
        assertEquals(4, rows)
    }
}
