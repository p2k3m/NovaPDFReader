package com.novapdf.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.DynamicScheme
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot

private val FallbackLightColorScheme = lightColorScheme(
    primary = PrimaryRed,
    onPrimary = ColorPalette.White,
    secondary = SecondaryBlue,
    onSecondary = ColorPalette.White,
    background = BackgroundLight,
    onBackground = ColorPalette.Black,
    surface = SurfaceLight,
    onSurface = ColorPalette.Black,
    tertiary = SecondaryTeal
)

private val FallbackDarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = ColorPalette.White,
    secondary = SecondaryTeal,
    onSecondary = ColorPalette.Black,
    background = BackgroundDark,
    onBackground = ColorPalette.White,
    surface = SurfaceDark,
    onSurface = ColorPalette.White,
    tertiary = AnnotationPink
)

@Composable
fun NovaPdfTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    highContrast: Boolean = false,
    seedColor: Color = PrimaryRed,
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val contrastLevel = if (highContrast) HIGH_CONTRAST_LEVEL else DEFAULT_CONTRAST_LEVEL
            materialColorSchemeFromSeed(
                seedColor = seedColor,
                darkTheme = useDarkTheme,
                contrastLevel = contrastLevel
            )
        }

        useDarkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

internal fun materialColorSchemeFromSeed(
    seedColor: Color,
    darkTheme: Boolean,
    contrastLevel: Double
): ColorScheme {
    val dynamicColors = MaterialDynamicColors()
    val scheme = SchemeTonalSpot(
        Hct.fromInt(seedColor.toArgb()),
        darkTheme,
        contrastLevel
    )
    return ColorScheme(
        primary = dynamicColors.primary().toComposeColor(scheme),
        onPrimary = dynamicColors.onPrimary().toComposeColor(scheme),
        primaryContainer = dynamicColors.primaryContainer().toComposeColor(scheme),
        onPrimaryContainer = dynamicColors.onPrimaryContainer().toComposeColor(scheme),
        inversePrimary = dynamicColors.inversePrimary().toComposeColor(scheme),
        secondary = dynamicColors.secondary().toComposeColor(scheme),
        onSecondary = dynamicColors.onSecondary().toComposeColor(scheme),
        secondaryContainer = dynamicColors.secondaryContainer().toComposeColor(scheme),
        onSecondaryContainer = dynamicColors.onSecondaryContainer().toComposeColor(scheme),
        tertiary = dynamicColors.tertiary().toComposeColor(scheme),
        onTertiary = dynamicColors.onTertiary().toComposeColor(scheme),
        tertiaryContainer = dynamicColors.tertiaryContainer().toComposeColor(scheme),
        onTertiaryContainer = dynamicColors.onTertiaryContainer().toComposeColor(scheme),
        background = dynamicColors.background().toComposeColor(scheme),
        onBackground = dynamicColors.onBackground().toComposeColor(scheme),
        surface = dynamicColors.surface().toComposeColor(scheme),
        onSurface = dynamicColors.onSurface().toComposeColor(scheme),
        surfaceVariant = dynamicColors.surfaceVariant().toComposeColor(scheme),
        onSurfaceVariant = dynamicColors.onSurfaceVariant().toComposeColor(scheme),
        surfaceTint = dynamicColors.surfaceTint().toComposeColor(scheme),
        inverseSurface = dynamicColors.inverseSurface().toComposeColor(scheme),
        inverseOnSurface = dynamicColors.inverseOnSurface().toComposeColor(scheme),
        error = dynamicColors.error().toComposeColor(scheme),
        onError = dynamicColors.onError().toComposeColor(scheme),
        errorContainer = dynamicColors.errorContainer().toComposeColor(scheme),
        onErrorContainer = dynamicColors.onErrorContainer().toComposeColor(scheme),
        outline = dynamicColors.outline().toComposeColor(scheme),
        outlineVariant = dynamicColors.outlineVariant().toComposeColor(scheme),
        scrim = dynamicColors.scrim().toComposeColor(scheme)
    )
}

private fun DynamicColor.toComposeColor(scheme: DynamicScheme): Color {
    return Color(getArgb(scheme).toLong() and 0xFFFFFFFFL)
}

private const val DEFAULT_CONTRAST_LEVEL = 0.0
private const val HIGH_CONTRAST_LEVEL = 0.7
