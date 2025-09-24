package com.novapdf.reader.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.test.assertTrue
import org.junit.Test

class DynamicColorSchemeTest {

    @Test
    fun primaryContrastMeetsGuidelines() {
        val seedColor = Color(0xFF6750A4)
        val colorScheme = materialColorSchemeFromSeed(
            seedColor = seedColor,
            darkTheme = false,
            contrastLevel = 0.0
        )
        assertContrastAtLeast(colorScheme.primary, colorScheme.onPrimary, 4.5)
        assertContrastAtLeast(colorScheme.surface, colorScheme.onSurface, 4.5)
    }

    @Test
    fun highContrastElevatesContrast() {
        val seedColor = Color(0xFF6750A4)
        val defaultScheme = materialColorSchemeFromSeed(
            seedColor = seedColor,
            darkTheme = false,
            contrastLevel = 0.0
        )
        val highContrastScheme = materialColorSchemeFromSeed(
            seedColor = seedColor,
            darkTheme = false,
            contrastLevel = 0.7
        )
        val defaultContrast = ColorUtils.calculateContrast(
            defaultScheme.onPrimary.toArgb(),
            defaultScheme.primary.toArgb()
        )
        val highContrast = ColorUtils.calculateContrast(
            highContrastScheme.onPrimary.toArgb(),
            highContrastScheme.primary.toArgb()
        )
        assertTrue(highContrast >= defaultContrast)
        assertTrue(highContrast >= 7.0)
    }

    private fun assertContrastAtLeast(background: Color, foreground: Color, minimumRatio: Double) {
        val contrast = ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb())
        assertTrue(contrast >= minimumRatio)
    }
}
