package com.novapdf.reader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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
    content: @Composable () -> Unit
) {
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        useDarkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
